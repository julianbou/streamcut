-- Patch: add the six RPCs the client calls that schema.sql never defined.
--
--   sync_get_library_delta_cursor, sync_pull_library_delta,
--   sync_push_library_items, sync_delete_library_items
--       Library delta sync. Without them the library step of every sync
--       fails with PGRST202 ("Could not find the function ... in the schema
--       cache"), so saved titles never reach the account. Backed by a new
--       append-only event log, library_items_events, in the same shape as
--       watch_progress_events and watched_items_events.
--   register_current_device
--       Records which installations are signed in (one row per install,
--       refreshed at most every 15 minutes by the client).
--   get_my_member_access
--       Upstream Nuvio's supporter tiers (cosmetic app themes). StreamCut has
--       no membership, so this returns no rows, which the client reads as
--       "no membership" -- it only needs the call to exist.
--
-- Run this in the Supabase SQL editor. Safe to re-run. schema.sql carries the
-- same block, so fresh projects get it from there.

-- ============================================================================
-- Library delta sync
-- ============================================================================

create table if not exists public.library_items_events (
    event_id bigint generated always as identity primary key,
    user_id uuid not null references auth.users (id) on delete cascade,
    profile_id int not null,
    operation text not null check (operation in ('upsert', 'delete')),
    content_id text not null,
    content_type text not null default '',
    name text not null default '',
    poster text,
    poster_shape text not null default 'POSTER',
    background text,
    description text,
    release_info text,
    imdb_rating double precision,
    genres text[],
    addon_base_url text,
    added_at bigint not null default 0,
    created_at timestamptz not null default now()
);
create index if not exists library_items_events_lookup
    on public.library_items_events (user_id, profile_id, event_id);

alter table public.library_items_events enable row level security;
drop policy if exists owner_all on public.library_items_events;
create policy owner_all on public.library_items_events for all to authenticated
    using (user_id = auth.uid()) with check (user_id = auth.uid());

create or replace function public.sync_get_library_delta_cursor(p_profile_id int)
returns bigint
language sql security definer
set search_path = public, pg_temp
as $$
    select coalesce(max(event_id), 0)
    from public.library_items_events
    where user_id = auth.uid() and profile_id = p_profile_id;
$$;

create or replace function public.sync_pull_library_delta(
    p_profile_id int,
    p_since_event_id bigint default 0,
    p_limit int default 500
)
returns table (
    event_id bigint,
    operation text,
    content_id text,
    content_type text,
    name text,
    poster text,
    poster_shape text,
    background text,
    description text,
    release_info text,
    imdb_rating double precision,
    genres text[],
    addon_base_url text,
    added_at bigint
)
language sql security definer
set search_path = public, pg_temp
as $$
    select e.event_id, e.operation, e.content_id, e.content_type, e.name, e.poster,
           e.poster_shape, e.background, e.description, e.release_info, e.imdb_rating,
           e.genres, e.addon_base_url, e.added_at
    from public.library_items_events e
    where e.user_id = auth.uid()
      and e.profile_id = p_profile_id
      and e.event_id > coalesce(p_since_event_id, 0)
    order by e.event_id
    limit coalesce(p_limit, 500);
$$;

create or replace function public.sync_push_library_items(
    p_profile_id int,
    p_items jsonb,
    p_origin_client_id text default null
)
returns void
language plpgsql security definer
set search_path = public, pg_temp
as $$
declare
    uid uuid := public._require_uid();
    item jsonb;
    item_genres text[];
begin
    -- Upserts only the items sent (unlike sync_push_library, which replaces
    -- the whole library with a snapshot), and logs each one for delta pulls.
    for item in select * from jsonb_array_elements(coalesce(p_items, '[]'::jsonb)) loop
        if item->>'content_id' is null then
            continue;
        end if;

        item_genres := case
            when item->'genres' is null or jsonb_typeof(item->'genres') <> 'array' then null
            else (select array_agg(g) from jsonb_array_elements_text(item->'genres') g)
        end;

        insert into public.library_items (
            user_id, profile_id, content_id, content_type, name, poster, poster_shape,
            background, description, release_info, imdb_rating, genres, addon_base_url, added_at
        ) values (
            uid, p_profile_id,
            item->>'content_id',
            coalesce(item->>'content_type', ''),
            coalesce(item->>'name', ''),
            item->>'poster',
            coalesce(item->>'poster_shape', 'POSTER'),
            item->>'background',
            item->>'description',
            item->>'release_info',
            (item->>'imdb_rating')::double precision,
            item_genres,
            item->>'addon_base_url',
            coalesce((item->>'added_at')::bigint, 0)
        )
        on conflict (user_id, profile_id, content_id, content_type) do update set
            name = excluded.name,
            poster = excluded.poster,
            poster_shape = excluded.poster_shape,
            background = excluded.background,
            description = excluded.description,
            release_info = excluded.release_info,
            imdb_rating = excluded.imdb_rating,
            genres = excluded.genres,
            addon_base_url = excluded.addon_base_url,
            added_at = excluded.added_at;

        insert into public.library_items_events (
            user_id, profile_id, operation, content_id, content_type, name, poster,
            poster_shape, background, description, release_info, imdb_rating, genres,
            addon_base_url, added_at
        ) values (
            uid, p_profile_id, 'upsert',
            item->>'content_id',
            coalesce(item->>'content_type', ''),
            coalesce(item->>'name', ''),
            item->>'poster',
            coalesce(item->>'poster_shape', 'POSTER'),
            item->>'background',
            item->>'description',
            item->>'release_info',
            (item->>'imdb_rating')::double precision,
            item_genres,
            item->>'addon_base_url',
            coalesce((item->>'added_at')::bigint, 0)
        );
    end loop;

    perform public._sync_invalidate('library', p_profile_id, p_origin_client_id);
end;
$$;

create or replace function public.sync_delete_library_items(
    p_profile_id int,
    p_keys jsonb,
    p_origin_client_id text default null
)
returns void
language plpgsql security definer
set search_path = public, pg_temp
as $$
declare
    uid uuid := public._require_uid();
    k jsonb;
begin
    -- Keys arrive as [{"content_id": ..., "content_type": ...}, ...].
    for k in select * from jsonb_array_elements(coalesce(p_keys, '[]'::jsonb)) loop
        if k->>'content_id' is null then
            continue;
        end if;

        delete from public.library_items
        where user_id = uid
          and profile_id = p_profile_id
          and content_id = k->>'content_id'
          and content_type = coalesce(k->>'content_type', '');

        insert into public.library_items_events (user_id, profile_id, operation, content_id, content_type)
        values (uid, p_profile_id, 'delete', k->>'content_id', coalesce(k->>'content_type', ''));
    end loop;

    perform public._sync_invalidate('library', p_profile_id, p_origin_client_id);
end;
$$;

-- ============================================================================
-- Device sessions
-- ============================================================================

create table if not exists public.user_devices (
    user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
    installation_id text not null,
    client_name text not null default '',
    client_version text not null default '',
    platform text not null default '',
    device_name text not null default '',
    first_seen_at timestamptz not null default now(),
    last_seen_at timestamptz not null default now(),
    primary key (user_id, installation_id)
);

alter table public.user_devices enable row level security;
drop policy if exists owner_all on public.user_devices;
create policy owner_all on public.user_devices for all to authenticated
    using (user_id = auth.uid()) with check (user_id = auth.uid());

create or replace function public.register_current_device(
    p_installation_id text,
    p_client_name text default '',
    p_client_version text default '',
    p_platform text default '',
    p_device_name text default ''
)
returns void
language plpgsql security definer
set search_path = public, pg_temp
as $$
declare uid uuid := public._require_uid();
begin
    if coalesce(p_installation_id, '') = '' then
        return;
    end if;

    insert into public.user_devices (
        user_id, installation_id, client_name, client_version, platform, device_name
    ) values (
        uid, p_installation_id,
        coalesce(p_client_name, ''), coalesce(p_client_version, ''),
        coalesce(p_platform, ''), coalesce(p_device_name, '')
    )
    on conflict (user_id, installation_id) do update set
        client_name = excluded.client_name,
        client_version = excluded.client_version,
        platform = excluded.platform,
        device_name = excluded.device_name,
        last_seen_at = now();
end;
$$;

-- ============================================================================
-- Membership (upstream supporter tiers; none in StreamCut)
-- ============================================================================

create or replace function public.get_my_member_access()
returns table (tier text, entitlements text[])
language sql security definer
set search_path = public, pg_temp
as $$
    -- No rows = no membership. The client then offers only the default look.
    select null::text, null::text[] where false;
$$;

-- ============================================================================
-- Grants: signed-in users only, like every other sync RPC.
-- ============================================================================

do $$
declare fn text;
begin
    foreach fn in array array[
        'sync_get_library_delta_cursor(int)',
        'sync_pull_library_delta(int, bigint, int)',
        'sync_push_library_items(int, jsonb, text)',
        'sync_delete_library_items(int, jsonb, text)',
        'register_current_device(text, text, text, text, text)',
        'get_my_member_access()'
    ] loop
        execute format('revoke execute on function public.%s from public, anon', fn);
        execute format('grant execute on function public.%s to authenticated', fn);
    end loop;
end
$$;
