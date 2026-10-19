-- Patch: trial accounts.
--
-- A trial account is an ordinary email/password user with a row in
-- public.trial_accounts. It comes with addons preloaded server-side and marked
-- `hidden`: the app uses them for search and streams but never lists them, and
-- the account cannot edit or delete them. While a trial is active the app logs
-- what it does (app opens, streams played, clips exported) to
-- public.usage_events; nothing is logged for regular accounts.
--
-- When a trial expires or is revoked, its hidden addons stop being readable,
-- and on the next start the app signs the account out.
--
-- Admins (rows in public.app_admins) read everything through the admin_*
-- functions below; that is what scripts/trials/dashboard.html uses.
--
-- Run this in the SQL editor after schema.sql. Safe to re-run.
-- Then make yourself an admin:
--   insert into public.app_admins (user_id)
--   select id from auth.users where email = 'you@example.com'
--   on conflict do nothing;

-- ============================================================================
-- Tables
-- ============================================================================

alter table public.addons add column if not exists hidden boolean not null default false;

create table if not exists public.trial_accounts (
    user_id uuid primary key references auth.users (id) on delete cascade,
    label text not null,
    email text not null default '',
    expires_at timestamptz,
    revoked_at timestamptz,
    notes text not null default '',
    created_at timestamptz not null default now()
);

create table if not exists public.usage_events (
    id bigint generated always as identity primary key,
    user_id uuid not null references auth.users (id) on delete cascade,
    kind text not null,
    detail jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create index if not exists usage_events_user_time
    on public.usage_events (user_id, created_at desc);

create table if not exists public.app_admins (
    user_id uuid primary key references auth.users (id) on delete cascade
);

-- No policies: these are reached only through the security definer functions
-- below (and the service role, which the trial script uses).
alter table public.trial_accounts enable row level security;
alter table public.usage_events enable row level security;
alter table public.app_admins enable row level security;

-- ============================================================================
-- Helpers
-- ============================================================================

-- True for regular accounts, and for trials that are neither revoked nor expired.
create or replace function public._trial_allows(p_user_id uuid)
returns boolean
language sql stable security definer
set search_path = public, pg_temp
as $$
    select coalesce((
        select t.revoked_at is null and (t.expires_at is null or t.expires_at > now())
        from public.trial_accounts t
        where t.user_id = p_user_id
    ), true);
$$;

create or replace function public._require_admin()
returns uuid
language plpgsql stable security definer
set search_path = public, pg_temp
as $$
declare uid uuid := public._require_uid();
begin
    if not exists (select 1 from public.app_admins where user_id = uid) then
        raise exception 'Not an admin';
    end if;
    return uid;
end;
$$;

-- ============================================================================
-- Hidden addons: readable only while the trial is live, never writable
-- ============================================================================

-- Restrictive policies are ANDed with owner_all from schema.sql.
drop policy if exists hidden_read on public.addons;
create policy hidden_read on public.addons as restrictive for select to authenticated
    using (not hidden or public._trial_allows(auth.uid()));

drop policy if exists hidden_insert on public.addons;
create policy hidden_insert on public.addons as restrictive for insert to authenticated
    with check (not hidden);

drop policy if exists hidden_update on public.addons;
create policy hidden_update on public.addons as restrictive for update to authenticated
    using (not hidden) with check (not hidden);

drop policy if exists hidden_delete on public.addons;
create policy hidden_delete on public.addons as restrictive for delete to authenticated
    using (not hidden);

-- Same as schema.sql, except the client's list replaces only the visible
-- addons: hidden rows survive every push and a pushed URL never overwrites one.
create or replace function public.sync_push_addons(
    p_profile_id int,
    p_addons jsonb,
    p_origin_client_id text default null
)
returns void
language plpgsql security definer
set search_path = public, pg_temp
as $$
declare uid uuid := public._require_uid();
begin
    delete from public.addons
    where user_id = uid and profile_id = p_profile_id and not hidden;

    insert into public.addons (user_id, profile_id, url, name, enabled, sort_order)
    select uid, p_profile_id,
           item->>'url',
           coalesce(item->>'name', ''),
           coalesce((item->>'enabled')::boolean, true),
           coalesce((item->>'sort_order')::int, 0)
    from jsonb_array_elements(coalesce(p_addons, '[]'::jsonb)) item
    where item->>'url' is not null
    on conflict (user_id, profile_id, url) do update set
        name = excluded.name,
        enabled = excluded.enabled,
        sort_order = excluded.sort_order,
        updated_at = now()
    where not public.addons.hidden;

    perform public._sync_invalidate('addons', p_profile_id, p_origin_client_id);
end;
$$;

-- ============================================================================
-- Client RPCs
-- ============================================================================

create or replace function public.trial_status()
returns table (is_trial boolean, active boolean, expires_at timestamptz)
language sql stable security definer
set search_path = public, pg_temp
as $$
    select t.user_id is not null,
           public._trial_allows(public._require_uid()),
           t.expires_at
    from (select 1) one
    left join public.trial_accounts t on t.user_id = auth.uid();
$$;

-- Records one event for a live trial account; a no-op for everyone else.
create or replace function public.log_usage(p_kind text, p_detail jsonb default '{}'::jsonb)
returns void
language plpgsql security definer
set search_path = public, pg_temp
as $$
declare uid uuid := public._require_uid();
begin
    if p_kind not in ('app_open', 'stream_play', 'clip_export') then
        return;
    end if;
    if not exists (select 1 from public.trial_accounts where user_id = uid) then
        return;
    end if;
    if not public._trial_allows(uid) then
        return;
    end if;
    if length(coalesce(p_detail, '{}'::jsonb)::text) > 2000 then
        p_detail := '{}'::jsonb;
    end if;
    insert into public.usage_events (user_id, kind, detail)
    values (uid, p_kind, coalesce(p_detail, '{}'::jsonb));
end;
$$;

-- ============================================================================
-- Admin RPCs (dashboard)
-- ============================================================================

create or replace function public.admin_trial_overview()
returns table (
    user_id uuid,
    label text,
    email text,
    created_at timestamptz,
    expires_at timestamptz,
    revoked_at timestamptz,
    last_seen timestamptz,
    opens bigint,
    plays bigint,
    clips bigint,
    plays_7d bigint,
    clips_7d bigint
)
language plpgsql stable security definer
set search_path = public, pg_temp
as $$
begin
    perform public._require_admin();
    return query
    select t.user_id, t.label, t.email, t.created_at, t.expires_at, t.revoked_at,
           max(e.created_at),
           count(*) filter (where e.kind = 'app_open'),
           count(*) filter (where e.kind = 'stream_play'),
           count(*) filter (where e.kind = 'clip_export'),
           count(*) filter (where e.kind = 'stream_play' and e.created_at > now() - interval '7 days'),
           count(*) filter (where e.kind = 'clip_export' and e.created_at > now() - interval '7 days')
    from public.trial_accounts t
    left join public.usage_events e on e.user_id = t.user_id
    group by t.user_id
    order by max(e.created_at) desc nulls last, t.created_at desc;
end;
$$;

create or replace function public.admin_trial_events(p_user_id uuid, p_limit int default 200)
returns table (kind text, detail jsonb, created_at timestamptz)
language plpgsql stable security definer
set search_path = public, pg_temp
as $$
begin
    perform public._require_admin();
    return query
    select e.kind, e.detail, e.created_at
    from public.usage_events e
    where e.user_id = p_user_id
    order by e.created_at desc
    limit least(greatest(coalesce(p_limit, 200), 1), 1000);
end;
$$;

-- Revoking also deletes the hidden addons and pokes the account's clients, so
-- an app that is open drops them right away instead of on its next start.
create or replace function public.admin_set_trial(
    p_user_id uuid,
    p_expires_at timestamptz,
    p_revoked boolean
)
returns void
language plpgsql security definer
set search_path = public, pg_temp
as $$
begin
    perform public._require_admin();

    update public.trial_accounts
    set expires_at = p_expires_at,
        revoked_at = case
            when p_revoked then coalesce(revoked_at, now())
            else null
        end
    where user_id = p_user_id;

    if p_revoked then
        delete from public.addons where user_id = p_user_id and hidden;
        insert into public.sync_invalidations (user_id, origin_client_id, surface, profile_id)
        values (p_user_id, 'admin', 'addons', 1);
    end if;
end;
$$;

-- ============================================================================
-- Grants
-- ============================================================================

do $$
declare fn text;
begin
    foreach fn in array array[
        '_trial_allows(uuid)',
        '_require_admin()',
        'sync_push_addons(int, jsonb, text)',
        'trial_status()',
        'log_usage(text, jsonb)',
        'admin_trial_overview()',
        'admin_trial_events(uuid, int)',
        'admin_set_trial(uuid, timestamptz, boolean)'
    ] loop
        execute format('revoke all on function public.%s from public, anon', fn);
        execute format('grant execute on function public.%s to authenticated', fn);
    end loop;
end
$$;
