-- ============================================================================
-- Nuvio-Clipper backend schema for a self-hosted Supabase project
-- ============================================================================
-- Run this whole file in the Supabase SQL editor (Dashboard -> SQL Editor).
-- It is idempotent: safe to re-run after edits.
--
-- What the app expects from the project besides this SQL:
--   1. Email/password auth enabled (Authentication -> Providers -> Email).
--      For instant sign-up during testing, disable "Confirm email".
--   2. (Optional) a PUBLIC storage bucket named "avatars" holding avatar
--      images referenced by avatar_catalog.storage_path. Without it, profiles
--      simply fall back to colored initials - everything else works.
--   3. (Optional) an Edge Function named "delete-account" used by the
--      "Delete account" button in settings. Without it that one button fails;
--      nothing else is affected.
--
-- Conventions used by the app (reverse-engineered from the client):
--   * "p_profile_id" in every RPC is the per-user profile INDEX (1..6),
--     not a global row id. All data tables are keyed (user_id, profile_id).
--   * Push RPCs receive "p_origin_client_id"; every push inserts a row into
--     public.sync_invalidations, which clients watch over Realtime and use
--     to trigger pulls (skipping their own origin id).
--   * Delta feeds for watch progress / watched items are append-only event
--     logs with operations 'upsert' and 'delete'.
-- ============================================================================

create extension if not exists pgcrypto with schema extensions;

-- ============================================================================
-- Tables
-- ============================================================================

create table if not exists public.profiles (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
    profile_index int not null,
    name text not null default 'Profile',
    avatar_color_hex text not null default '#1E88E5',
    avatar_id text,
    avatar_url text,
    uses_primary_addons boolean not null default false,
    uses_primary_plugins boolean not null default false,
    pin_hash text,
    pin_enabled boolean not null default false,
    pin_failed_attempts int not null default 0,
    pin_locked_until timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (user_id, profile_index)
);

create table if not exists public.addons (
    user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
    profile_id int not null,
    url text not null,
    name text not null default '',
    enabled boolean not null default true,
    sort_order int not null default 0,
    updated_at timestamptz not null default now(),
    primary key (user_id, profile_id, url)
);

create table if not exists public.plugins (
    user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
    profile_id int not null,
    url text not null,
    name text not null default '',
    enabled boolean not null default true,
    sort_order int not null default 0,
    updated_at timestamptz not null default now(),
    primary key (user_id, profile_id, url)
);

create table if not exists public.watch_progress (
    user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
    profile_id int not null,
    progress_key text not null,
    content_id text not null default '',
    content_type text not null default '',
    video_id text not null default '',
    season int,
    episode int,
    "position" bigint not null default 0,
    duration bigint not null default 0,
    last_watched bigint not null default 0,
    primary key (user_id, profile_id, progress_key)
);

create table if not exists public.watch_progress_events (
    event_id bigint generated always as identity primary key,
    user_id uuid not null references auth.users (id) on delete cascade,
    profile_id int not null,
    operation text not null check (operation in ('upsert', 'delete')),
    progress_key text not null,
    content_id text not null default '',
    content_type text not null default '',
    video_id text not null default '',
    season int,
    episode int,
    "position" bigint not null default 0,
    duration bigint not null default 0,
    last_watched bigint not null default 0,
    created_at timestamptz not null default now()
);
create index if not exists watch_progress_events_lookup
    on public.watch_progress_events (user_id, profile_id, event_id);

create table if not exists public.watched_items (
    user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
    profile_id int not null,
    content_id text not null,
    content_type text not null default '',
    title text not null default '',
    season int,
    episode int,
    watched_at bigint not null default 0
);
create unique index if not exists watched_items_key
    on public.watched_items (user_id, profile_id, content_id, coalesce(season, -1), coalesce(episode, -1));

create table if not exists public.watched_items_events (
    event_id bigint generated always as identity primary key,
    user_id uuid not null references auth.users (id) on delete cascade,
    profile_id int not null,
    operation text not null check (operation in ('upsert', 'delete')),
    content_id text not null,
    content_type text not null default '',
    title text not null default '',
    season int,
    episode int,
    watched_at bigint not null default 0,
    created_at timestamptz not null default now()
);
create index if not exists watched_items_events_lookup
    on public.watched_items_events (user_id, profile_id, event_id);

create table if not exists public.library_items (
    user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
    profile_id int not null,
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
    primary key (user_id, profile_id, content_id, content_type)
);

create table if not exists public.profile_settings (
    user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
    profile_id int not null,
    platform text not null,
    settings_json jsonb not null default '{}'::jsonb,
    updated_at timestamptz not null default now(),
    primary key (user_id, profile_id, platform)
);

create table if not exists public.home_catalog_settings (
    user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
    profile_id int not null,
    platform text not null,
    settings_json jsonb not null default '{}'::jsonb,
    updated_at timestamptz not null default now(),
    primary key (user_id, profile_id, platform)
);

create table if not exists public.collections (
    user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
    profile_id int not null,
    collections_json jsonb,
    updated_at timestamptz not null default now(),
    primary key (user_id, profile_id)
);

create table if not exists public.provider_credentials (
    user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
    profile_id int not null,
    provider text not null,
    credential_json jsonb not null default '{}'::jsonb,
    updated_at timestamptz not null default now(),
    primary key (user_id, profile_id, provider)
);

create table if not exists public.sync_invalidations (
    id bigint generated always as identity primary key,
    user_id uuid not null references auth.users (id) on delete cascade,
    origin_client_id text,
    surface text not null,
    profile_id int,
    created_at timestamptz not null default now()
);
create index if not exists sync_invalidations_lookup
    on public.sync_invalidations (user_id, created_at);

create table if not exists public.avatar_catalog (
    id text primary key,
    display_name text not null default '',
    storage_path text not null default '',
    category text not null default 'character',
    sort_order int not null default 0,
    is_active boolean not null default true,
    bg_color text
);

-- ============================================================================
-- Row Level Security (owner-only on user data; catalog readable by everyone)
-- ============================================================================

alter table public.profiles enable row level security;
alter table public.addons enable row level security;
alter table public.plugins enable row level security;
alter table public.watch_progress enable row level security;
alter table public.watch_progress_events enable row level security;
alter table public.watched_items enable row level security;
alter table public.watched_items_events enable row level security;
alter table public.library_items enable row level security;
alter table public.profile_settings enable row level security;
alter table public.home_catalog_settings enable row level security;
alter table public.collections enable row level security;
alter table public.provider_credentials enable row level security;
alter table public.sync_invalidations enable row level security;
alter table public.avatar_catalog enable row level security;

do $$
declare t text;
begin
    foreach t in array array[
        'profiles', 'addons', 'plugins', 'watch_progress', 'watch_progress_events',
        'watched_items', 'watched_items_events', 'library_items', 'profile_settings',
        'home_catalog_settings', 'collections', 'provider_credentials', 'sync_invalidations'
    ] loop
        execute format('drop policy if exists owner_all on public.%I', t);
        execute format(
            'create policy owner_all on public.%I for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid())',
            t
        );
    end loop;
end
$$;

drop policy if exists catalog_read on public.avatar_catalog;
create policy catalog_read on public.avatar_catalog for select to authenticated, anon using (true);

-- Realtime: clients subscribe to INSERTs on public.sync_invalidations.
do $$
begin
    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'sync_invalidations'
    ) then
        alter publication supabase_realtime add table public.sync_invalidations;
    end if;
end
$$;

-- ============================================================================
-- Helpers
-- ============================================================================

create or replace function public._require_uid()
returns uuid
language plpgsql stable
set search_path = public, pg_temp
as $$
declare uid uuid := auth.uid();
begin
    if uid is null then
        raise exception 'Not authenticated';
    end if;
    return uid;
end;
$$;

create or replace function public._sync_invalidate(
    p_surface text,
    p_profile_id int,
    p_origin_client_id text
)
returns void
language sql security definer
set search_path = public, pg_temp
as $$
    insert into public.sync_invalidations (user_id, origin_client_id, surface, profile_id)
    select auth.uid(), p_origin_client_id, p_surface, p_profile_id
    where auth.uid() is not null;
$$;

create or replace function public._touch_profiles_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

drop trigger if exists profiles_touch_updated_at on public.profiles;
create trigger profiles_touch_updated_at
    before update on public.profiles
    for each row execute function public._touch_profiles_updated_at();

-- Create a default profile for every new auth user.
create or replace function public._handle_new_user()
returns trigger
language plpgsql security definer
set search_path = public, pg_temp
as $$
begin
    insert into public.profiles (user_id, profile_index, name)
    values (new.id, 1, 'Profile 1')
    on conflict (user_id, profile_index) do nothing;
    return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
    after insert on auth.users
    for each row execute function public._handle_new_user();

-- ============================================================================
-- Profiles
-- ============================================================================

create or replace function public.sync_pull_profiles()
returns table (
    id uuid,
    user_id uuid,
    profile_index int,
    name text,
    avatar_color_hex text,
    avatar_id text,
    avatar_url text,
    uses_primary_addons boolean,
    uses_primary_plugins boolean,
    pin_enabled boolean,
    pin_locked_until timestamptz,
    created_at timestamptz,
    updated_at timestamptz
)
language plpgsql security definer
set search_path = public, pg_temp
as $$
declare uid uuid := public._require_uid();
begin
    -- Safety net for accounts created before this schema existed.
    if not exists (select 1 from public.profiles p where p.user_id = uid) then
        insert into public.profiles (user_id, profile_index, name)
        values (uid, 1, 'Profile 1')
        on conflict (user_id, profile_index) do nothing;
    end if;

    return query
        select p.id, p.user_id, p.profile_index, p.name, p.avatar_color_hex,
               p.avatar_id, p.avatar_url, p.uses_primary_addons, p.uses_primary_plugins,
               p.pin_enabled, p.pin_locked_until, p.created_at, p.updated_at
        from public.profiles p
        where p.user_id = uid
        order by p.profile_index;
end;
$$;

create or replace function public.sync_push_profiles(
    p_client_max_profiles int,
    p_profiles jsonb,
    p_origin_client_id text default null
)
returns void
language plpgsql security definer
set search_path = public, pg_temp
as $$
declare
    uid uuid := public._require_uid();
    max_profiles int := least(coalesce(p_client_max_profiles, 6), 6);
    item jsonb;
    idx int;
begin
    for item in select * from jsonb_array_elements(coalesce(p_profiles, '[]'::jsonb)) loop
        idx := (item->>'profile_index')::int;
        if idx is null or idx < 1 or idx > max_profiles then
            continue;
        end if;
        insert into public.profiles (
            user_id, profile_index, name, avatar_color_hex,
            uses_primary_addons, uses_primary_plugins, avatar_id, avatar_url
        ) values (
            uid, idx,
            coalesce(item->>'name', 'Profile'),
            coalesce(item->>'avatar_color_hex', '#1E88E5'),
            coalesce((item->>'uses_primary_addons')::boolean, false),
            coalesce((item->>'uses_primary_plugins')::boolean, false),
            item->>'avatar_id',
            item->>'avatar_url'
        )
        on conflict (user_id, profile_index) do update set
            name = excluded.name,
            avatar_color_hex = excluded.avatar_color_hex,
            uses_primary_addons = excluded.uses_primary_addons,
            uses_primary_plugins = excluded.uses_primary_plugins,
            avatar_id = excluded.avatar_id,
            avatar_url = excluded.avatar_url;
    end loop;

    perform public._sync_invalidate('profiles', null, p_origin_client_id);
end;
$$;

create or replace function public.sync_delete_profile_data(
    p_profile_id int,
    p_origin_client_id text default null
)
returns void
language plpgsql security definer
set search_path = public, pg_temp
as $$
declare uid uuid := public._require_uid();
begin
    delete from public.addons where user_id = uid and profile_id = p_profile_id;
    delete from public.plugins where user_id = uid and profile_id = p_profile_id;
    delete from public.watch_progress where user_id = uid and profile_id = p_profile_id;
    delete from public.watch_progress_events where user_id = uid and profile_id = p_profile_id;
    delete from public.watched_items where user_id = uid and profile_id = p_profile_id;
    delete from public.watched_items_events where user_id = uid and profile_id = p_profile_id;
    delete from public.library_items where user_id = uid and profile_id = p_profile_id;
    delete from public.profile_settings where user_id = uid and profile_id = p_profile_id;
    delete from public.home_catalog_settings where user_id = uid and profile_id = p_profile_id;
    delete from public.collections where user_id = uid and profile_id = p_profile_id;
    delete from public.provider_credentials where user_id = uid and profile_id = p_profile_id;
    delete from public.profiles where user_id = uid and profile_index = p_profile_id;

    perform public._sync_invalidate('profiles', p_profile_id, p_origin_client_id);
end;
$$;

create or replace function public.sync_pull_profile_locks()
returns table (profile_index int, pin_enabled boolean, pin_locked_until timestamptz)
language sql security definer
set search_path = public, pg_temp
as $$
    select p.profile_index, p.pin_enabled, p.pin_locked_until
    from public.profiles p
    where p.user_id = auth.uid()
    order by p.profile_index;
$$;

-- ============================================================================
-- Profile PINs
-- ============================================================================

create or replace function public.verify_profile_pin(p_profile_id int, p_pin text)
returns table (unlocked boolean, retry_after_seconds int, message text)
language plpgsql security definer
set search_path = public, extensions, pg_temp
as $$
declare
    uid uuid := public._require_uid();
    prof public.profiles%rowtype;
    lock_seconds int;
begin
    select * into prof
    from public.profiles
    where user_id = uid and profile_index = p_profile_id;

    if not found or prof.pin_hash is null then
        return query select true, 0, null::text;
        return;
    end if;

    if prof.pin_locked_until is not null and prof.pin_locked_until > now() then
        return query select
            false,
            greatest(1, ceil(extract(epoch from prof.pin_locked_until - now()))::int),
            'Too many attempts. Try again later.'::text;
        return;
    end if;

    if crypt(p_pin, prof.pin_hash) = prof.pin_hash then
        update public.profiles
        set pin_failed_attempts = 0, pin_locked_until = null
        where user_id = uid and profile_index = p_profile_id;
        return query select true, 0, null::text;
        return;
    end if;

    -- Wrong PIN: escalate lockout after 5 failures (60s, 120s, ... max 900s).
    update public.profiles
    set pin_failed_attempts = pin_failed_attempts + 1,
        pin_locked_until = case
            when pin_failed_attempts + 1 >= 5
                then now() + make_interval(secs => least(900, 60 * pow(2, pin_failed_attempts + 1 - 5)))
            else pin_locked_until
        end
    where user_id = uid and profile_index = p_profile_id
    returning
        case when pin_locked_until is not null and pin_locked_until > now()
            then greatest(1, ceil(extract(epoch from pin_locked_until - now()))::int)
            else 0
        end
    into lock_seconds;

    return query select false, coalesce(lock_seconds, 0), 'Incorrect PIN'::text;
end;
$$;

create or replace function public.set_profile_pin(
    p_profile_id int,
    p_pin text,
    p_current_pin text default null
)
returns void
language plpgsql security definer
set search_path = public, extensions, pg_temp
as $$
declare
    uid uuid := public._require_uid();
    prof public.profiles%rowtype;
begin
    if p_pin is null or length(p_pin) < 4 then
        raise exception 'PIN must be at least 4 digits';
    end if;

    select * into prof
    from public.profiles
    where user_id = uid and profile_index = p_profile_id;
    if not found then
        raise exception 'Profile not found';
    end if;

    if prof.pin_hash is not null
        and (p_current_pin is null or crypt(p_current_pin, prof.pin_hash) <> prof.pin_hash) then
        raise exception 'Current PIN is incorrect';
    end if;

    update public.profiles
    set pin_hash = crypt(p_pin, gen_salt('bf')),
        pin_enabled = true,
        pin_failed_attempts = 0,
        pin_locked_until = null
    where user_id = uid and profile_index = p_profile_id;
end;
$$;

create or replace function public.clear_profile_pin(
    p_profile_id int,
    p_current_pin text default null
)
returns void
language plpgsql security definer
set search_path = public, extensions, pg_temp
as $$
declare
    uid uuid := public._require_uid();
    prof public.profiles%rowtype;
begin
    select * into prof
    from public.profiles
    where user_id = uid and profile_index = p_profile_id;
    if not found then
        raise exception 'Profile not found';
    end if;

    if prof.pin_hash is not null
        and (p_current_pin is null or crypt(p_current_pin, prof.pin_hash) <> prof.pin_hash) then
        raise exception 'Current PIN is incorrect';
    end if;

    update public.profiles
    set pin_hash = null, pin_enabled = false,
        pin_failed_attempts = 0, pin_locked_until = null
    where user_id = uid and profile_index = p_profile_id;
end;
$$;

create or replace function public.clear_profile_pin_with_account_password(
    p_account_password text,
    p_profile_id int
)
returns void
language plpgsql security definer
set search_path = public, extensions, pg_temp
as $$
declare
    uid uuid := public._require_uid();
    stored_password text;
begin
    select encrypted_password into stored_password
    from auth.users
    where id = uid;

    if stored_password is null
        or crypt(p_account_password, stored_password) <> stored_password then
        raise exception 'Incorrect account password';
    end if;

    update public.profiles
    set pin_hash = null, pin_enabled = false,
        pin_failed_attempts = 0, pin_locked_until = null
    where user_id = uid and profile_index = p_profile_id;
end;
$$;

-- ============================================================================
-- Addons / Plugins (pull is a direct table select; push replaces the list)
-- ============================================================================

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
    delete from public.addons where user_id = uid and profile_id = p_profile_id;

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
        updated_at = now();

    perform public._sync_invalidate('addons', p_profile_id, p_origin_client_id);
end;
$$;

create or replace function public.sync_push_plugins(
    p_profile_id int,
    p_plugins jsonb,
    p_origin_client_id text default null
)
returns void
language plpgsql security definer
set search_path = public, pg_temp
as $$
declare uid uuid := public._require_uid();
begin
    delete from public.plugins where user_id = uid and profile_id = p_profile_id;

    insert into public.plugins (user_id, profile_id, url, name, enabled, sort_order)
    select uid, p_profile_id,
           item->>'url',
           coalesce(item->>'name', ''),
           coalesce((item->>'enabled')::boolean, true),
           coalesce((item->>'sort_order')::int, 0)
    from jsonb_array_elements(coalesce(p_plugins, '[]'::jsonb)) item
    where item->>'url' is not null
    on conflict (user_id, profile_id, url) do update set
        name = excluded.name,
        enabled = excluded.enabled,
        sort_order = excluded.sort_order,
        updated_at = now();

    perform public._sync_invalidate('plugins', p_profile_id, p_origin_client_id);
end;
$$;

-- ============================================================================
-- Watch progress
-- ============================================================================

create or replace function public.sync_get_watch_progress_delta_cursor(p_profile_id int)
returns bigint
language sql security definer
set search_path = public, pg_temp
as $$
    select coalesce(max(event_id), 0)
    from public.watch_progress_events
    where user_id = auth.uid() and profile_id = p_profile_id;
$$;

create or replace function public.sync_pull_watch_progress_delta(
    p_profile_id int,
    p_since_event_id bigint default 0,
    p_limit int default 500
)
returns table (
    event_id bigint,
    operation text,
    progress_key text,
    content_id text,
    content_type text,
    video_id text,
    season int,
    episode int,
    "position" bigint,
    duration bigint,
    last_watched bigint
)
language sql security definer
set search_path = public, pg_temp
as $$
    select e.event_id, e.operation, e.progress_key, e.content_id, e.content_type,
           e.video_id, e.season, e.episode, e."position", e.duration, e.last_watched
    from public.watch_progress_events e
    where e.user_id = auth.uid()
      and e.profile_id = p_profile_id
      and e.event_id > coalesce(p_since_event_id, 0)
    order by e.event_id
    limit coalesce(p_limit, 500);
$$;

create or replace function public.sync_pull_watch_progress(
    p_profile_id int,
    p_since_last_watched bigint default null,
    p_limit int default null
)
returns table (
    content_id text,
    content_type text,
    video_id text,
    season int,
    episode int,
    "position" bigint,
    duration bigint,
    last_watched bigint,
    progress_key text
)
language sql security definer
set search_path = public, pg_temp
as $$
    select w.content_id, w.content_type, w.video_id, w.season, w.episode,
           w."position", w.duration, w.last_watched, w.progress_key
    from public.watch_progress w
    where w.user_id = auth.uid()
      and w.profile_id = p_profile_id
      and (p_since_last_watched is null or w.last_watched > p_since_last_watched)
    order by w.last_watched desc
    limit coalesce(p_limit, 10000);
$$;

create or replace function public.sync_push_watch_progress(
    p_profile_id int,
    p_entries jsonb,
    p_origin_client_id text default null
)
returns void
language plpgsql security definer
set search_path = public, pg_temp
as $$
declare
    uid uuid := public._require_uid();
    item jsonb;
    key text;
begin
    for item in select * from jsonb_array_elements(coalesce(p_entries, '[]'::jsonb)) loop
        key := coalesce(nullif(item->>'progress_key', ''), item->>'content_id');
        if key is null then
            continue;
        end if;

        insert into public.watch_progress (
            user_id, profile_id, progress_key, content_id, content_type,
            video_id, season, episode, "position", duration, last_watched
        ) values (
            uid, p_profile_id, key,
            coalesce(item->>'content_id', ''),
            coalesce(item->>'content_type', ''),
            coalesce(item->>'video_id', ''),
            (item->>'season')::int,
            (item->>'episode')::int,
            coalesce((item->>'position')::bigint, 0),
            coalesce((item->>'duration')::bigint, 0),
            coalesce((item->>'last_watched')::bigint, 0)
        )
        on conflict (user_id, profile_id, progress_key) do update set
            content_id = excluded.content_id,
            content_type = excluded.content_type,
            video_id = excluded.video_id,
            season = excluded.season,
            episode = excluded.episode,
            "position" = excluded."position",
            duration = excluded.duration,
            last_watched = excluded.last_watched;

        insert into public.watch_progress_events (
            user_id, profile_id, operation, progress_key, content_id, content_type,
            video_id, season, episode, "position", duration, last_watched
        ) values (
            uid, p_profile_id, 'upsert', key,
            coalesce(item->>'content_id', ''),
            coalesce(item->>'content_type', ''),
            coalesce(item->>'video_id', ''),
            (item->>'season')::int,
            (item->>'episode')::int,
            coalesce((item->>'position')::bigint, 0),
            coalesce((item->>'duration')::bigint, 0),
            coalesce((item->>'last_watched')::bigint, 0)
        );
    end loop;

    perform public._sync_invalidate('watch_progress', p_profile_id, p_origin_client_id);
end;
$$;

create or replace function public.sync_delete_watch_progress(
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
    key text;
begin
    for key in select jsonb_array_elements_text(coalesce(p_keys, '[]'::jsonb)) loop
        delete from public.watch_progress
        where user_id = uid and profile_id = p_profile_id and progress_key = key;

        insert into public.watch_progress_events (user_id, profile_id, operation, progress_key)
        values (uid, p_profile_id, 'delete', key);
    end loop;

    perform public._sync_invalidate('watch_progress', p_profile_id, p_origin_client_id);
end;
$$;

-- ============================================================================
-- Watched items
-- ============================================================================

create or replace function public.sync_get_watched_items_delta_cursor(p_profile_id int)
returns bigint
language sql security definer
set search_path = public, pg_temp
as $$
    select coalesce(max(event_id), 0)
    from public.watched_items_events
    where user_id = auth.uid() and profile_id = p_profile_id;
$$;

create or replace function public.sync_pull_watched_items_delta(
    p_profile_id int,
    p_since_event_id bigint default 0,
    p_limit int default 500
)
returns table (
    event_id bigint,
    operation text,
    content_id text,
    content_type text,
    title text,
    season int,
    episode int,
    watched_at bigint
)
language sql security definer
set search_path = public, pg_temp
as $$
    select e.event_id, e.operation, e.content_id, e.content_type,
           e.title, e.season, e.episode, e.watched_at
    from public.watched_items_events e
    where e.user_id = auth.uid()
      and e.profile_id = p_profile_id
      and e.event_id > coalesce(p_since_event_id, 0)
    order by e.event_id
    limit coalesce(p_limit, 500);
$$;

create or replace function public.sync_pull_watched_items(
    p_profile_id int,
    p_page int default 0,
    p_page_size int default 500
)
returns table (
    content_id text,
    content_type text,
    title text,
    season int,
    episode int,
    watched_at bigint
)
language sql security definer
set search_path = public, pg_temp
as $$
    select w.content_id, w.content_type, w.title, w.season, w.episode, w.watched_at
    from public.watched_items w
    where w.user_id = auth.uid() and w.profile_id = p_profile_id
    order by w.watched_at desc, w.content_id, coalesce(w.season, -1), coalesce(w.episode, -1)
    limit coalesce(p_page_size, 500)
    offset coalesce(p_page, 0) * coalesce(p_page_size, 500);
$$;

create or replace function public.sync_push_watched_items(
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
begin
    for item in select * from jsonb_array_elements(coalesce(p_items, '[]'::jsonb)) loop
        if item->>'content_id' is null then
            continue;
        end if;

        insert into public.watched_items (
            user_id, profile_id, content_id, content_type, title, season, episode, watched_at
        ) values (
            uid, p_profile_id,
            item->>'content_id',
            coalesce(item->>'content_type', ''),
            coalesce(item->>'title', ''),
            (item->>'season')::int,
            (item->>'episode')::int,
            coalesce((item->>'watched_at')::bigint, 0)
        )
        on conflict (user_id, profile_id, content_id, coalesce(season, -1), coalesce(episode, -1))
        do update set
            content_type = excluded.content_type,
            title = excluded.title,
            watched_at = excluded.watched_at;

        insert into public.watched_items_events (
            user_id, profile_id, operation, content_id, content_type, title, season, episode, watched_at
        ) values (
            uid, p_profile_id, 'upsert',
            item->>'content_id',
            coalesce(item->>'content_type', ''),
            coalesce(item->>'title', ''),
            (item->>'season')::int,
            (item->>'episode')::int,
            coalesce((item->>'watched_at')::bigint, 0)
        );
    end loop;

    perform public._sync_invalidate('watched_items', p_profile_id, p_origin_client_id);
end;
$$;

create or replace function public.sync_delete_watched_items(
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
    item jsonb;
begin
    for item in select * from jsonb_array_elements(coalesce(p_keys, '[]'::jsonb)) loop
        if item->>'content_id' is null then
            continue;
        end if;

        delete from public.watched_items w
        where w.user_id = uid
          and w.profile_id = p_profile_id
          and w.content_id = item->>'content_id'
          and coalesce(w.season, -1) = coalesce((item->>'season')::int, -1)
          and coalesce(w.episode, -1) = coalesce((item->>'episode')::int, -1);

        insert into public.watched_items_events (
            user_id, profile_id, operation, content_id, season, episode
        ) values (
            uid, p_profile_id, 'delete',
            item->>'content_id',
            (item->>'season')::int,
            (item->>'episode')::int
        );
    end loop;

    perform public._sync_invalidate('watched_items', p_profile_id, p_origin_client_id);
end;
$$;

-- ============================================================================
-- Library
-- ============================================================================

create or replace function public.sync_pull_library(
    p_profile_id int,
    p_limit int default 500,
    p_offset int default 0
)
returns table (
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
    select l.content_id, l.content_type, l.name, l.poster, l.poster_shape,
           l.background, l.description, l.release_info, l.imdb_rating,
           l.genres, l.addon_base_url, l.added_at
    from public.library_items l
    where l.user_id = auth.uid() and l.profile_id = p_profile_id
    order by l.added_at desc, l.content_id, l.content_type
    limit coalesce(p_limit, 500)
    offset coalesce(p_offset, 0);
$$;

create or replace function public.sync_push_library(
    p_profile_id int,
    p_items jsonb,
    p_origin_client_id text default null
)
returns void
language plpgsql security definer
set search_path = public, pg_temp
as $$
declare uid uuid := public._require_uid();
begin
    -- The client pushes its full library snapshot: replace everything.
    delete from public.library_items where user_id = uid and profile_id = p_profile_id;

    insert into public.library_items (
        user_id, profile_id, content_id, content_type, name, poster, poster_shape,
        background, description, release_info, imdb_rating, genres, addon_base_url, added_at
    )
    select uid, p_profile_id,
           item->>'content_id',
           coalesce(item->>'content_type', ''),
           coalesce(item->>'name', ''),
           item->>'poster',
           coalesce(item->>'poster_shape', 'POSTER'),
           item->>'background',
           item->>'description',
           item->>'release_info',
           (item->>'imdb_rating')::double precision,
           case
               when item->'genres' is null or jsonb_typeof(item->'genres') <> 'array' then null
               else (select array_agg(g) from jsonb_array_elements_text(item->'genres') g)
           end,
           item->>'addon_base_url',
           coalesce((item->>'added_at')::bigint, 0)
    from jsonb_array_elements(coalesce(p_items, '[]'::jsonb)) item
    where item->>'content_id' is not null
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

    perform public._sync_invalidate('library', p_profile_id, p_origin_client_id);
end;
$$;

-- ============================================================================
-- Settings blobs (profile settings + home catalog settings)
-- ============================================================================

create or replace function public.sync_pull_profile_settings_blob(
    p_profile_id int,
    p_platform text
)
returns table (profile_id int, settings_json jsonb, updated_at timestamptz)
language sql security definer
set search_path = public, pg_temp
as $$
    select s.profile_id, s.settings_json, s.updated_at
    from public.profile_settings s
    where s.user_id = auth.uid()
      and s.profile_id = p_profile_id
      and s.platform = p_platform;
$$;

create or replace function public.sync_push_profile_settings_blob(
    p_profile_id int,
    p_platform text,
    p_settings_json jsonb,
    p_origin_client_id text default null
)
returns void
language plpgsql security definer
set search_path = public, pg_temp
as $$
declare uid uuid := public._require_uid();
begin
    insert into public.profile_settings (user_id, profile_id, platform, settings_json, updated_at)
    values (uid, p_profile_id, p_platform, coalesce(p_settings_json, '{}'::jsonb), now())
    on conflict (user_id, profile_id, platform) do update set
        settings_json = excluded.settings_json,
        updated_at = now();

    perform public._sync_invalidate('profile_settings', p_profile_id, p_origin_client_id);
end;
$$;

create or replace function public.sync_pull_home_catalog_settings(
    p_profile_id int,
    p_platform text
)
returns table (profile_id int, settings_json jsonb, updated_at timestamptz)
language sql security definer
set search_path = public, pg_temp
as $$
    select s.profile_id, s.settings_json, s.updated_at
    from public.home_catalog_settings s
    where s.user_id = auth.uid()
      and s.profile_id = p_profile_id
      and s.platform = p_platform;
$$;

create or replace function public.sync_push_home_catalog_settings(
    p_profile_id int,
    p_platform text,
    p_settings_json jsonb,
    p_origin_client_id text default null
)
returns void
language plpgsql security definer
set search_path = public, pg_temp
as $$
declare uid uuid := public._require_uid();
begin
    insert into public.home_catalog_settings (user_id, profile_id, platform, settings_json, updated_at)
    values (uid, p_profile_id, p_platform, coalesce(p_settings_json, '{}'::jsonb), now())
    on conflict (user_id, profile_id, platform) do update set
        settings_json = excluded.settings_json,
        updated_at = now();

    perform public._sync_invalidate('home_catalog_settings', p_profile_id, p_origin_client_id);
end;
$$;

-- ============================================================================
-- Collections
-- ============================================================================

create or replace function public.sync_pull_collections(p_profile_id int)
returns table (profile_id int, collections_json jsonb, updated_at timestamptz)
language sql security definer
set search_path = public, pg_temp
as $$
    select c.profile_id, c.collections_json, c.updated_at
    from public.collections c
    where c.user_id = auth.uid() and c.profile_id = p_profile_id;
$$;

create or replace function public.sync_push_collections(
    p_profile_id int,
    p_collections_json jsonb,
    p_origin_client_id text default null
)
returns void
language plpgsql security definer
set search_path = public, pg_temp
as $$
declare uid uuid := public._require_uid();
begin
    insert into public.collections (user_id, profile_id, collections_json, updated_at)
    values (uid, p_profile_id, p_collections_json, now())
    on conflict (user_id, profile_id) do update set
        collections_json = excluded.collections_json,
        updated_at = now();

    perform public._sync_invalidate('collections', p_profile_id, p_origin_client_id);
end;
$$;

-- ============================================================================
-- Provider credentials (Trakt, etc.)
-- ============================================================================

create or replace function public.sync_pull_provider_credentials(p_profile_id int)
returns table (provider text, credential_json jsonb, updated_at timestamptz)
language sql security definer
set search_path = public, pg_temp
as $$
    select c.provider, c.credential_json, c.updated_at
    from public.provider_credentials c
    where c.user_id = auth.uid() and c.profile_id = p_profile_id;
$$;

create or replace function public.sync_push_provider_credentials(
    p_profile_id int,
    p_credentials jsonb,
    p_origin_client_id text default null
)
returns void
language plpgsql security definer
set search_path = public, pg_temp
as $$
declare uid uuid := public._require_uid();
begin
    insert into public.provider_credentials (user_id, profile_id, provider, credential_json, updated_at)
    select uid, p_profile_id,
           item->>'provider',
           coalesce(item->'credential_json', '{}'::jsonb),
           now()
    from jsonb_array_elements(coalesce(p_credentials, '[]'::jsonb)) item
    where item->>'provider' is not null
    on conflict (user_id, profile_id, provider) do update set
        credential_json = excluded.credential_json,
        updated_at = now();
end;
$$;

create or replace function public.sync_delete_provider_credentials(
    p_profile_id int,
    p_provider text,
    p_origin_client_id text default null
)
returns void
language plpgsql security definer
set search_path = public, pg_temp
as $$
declare uid uuid := public._require_uid();
begin
    delete from public.provider_credentials
    where user_id = uid and profile_id = p_profile_id and provider = p_provider;
end;
$$;

-- ============================================================================
-- Avatar catalog
-- ============================================================================

create or replace function public.get_avatar_catalog()
returns setof public.avatar_catalog
language sql security definer
set search_path = public, pg_temp
as $$
    select * from public.avatar_catalog
    where is_active
    order by sort_order, id;
$$;

-- ============================================================================
-- Grants: RPCs callable only by signed-in users (avatar catalog also by anon)
-- ============================================================================

do $$
declare fn text;
begin
    foreach fn in array array[
        'sync_pull_profiles()',
        'sync_push_profiles(int, jsonb, text)',
        'sync_delete_profile_data(int, text)',
        'sync_pull_profile_locks()',
        'verify_profile_pin(int, text)',
        'set_profile_pin(int, text, text)',
        'clear_profile_pin(int, text)',
        'clear_profile_pin_with_account_password(text, int)',
        'sync_push_addons(int, jsonb, text)',
        'sync_push_plugins(int, jsonb, text)',
        'sync_get_watch_progress_delta_cursor(int)',
        'sync_pull_watch_progress_delta(int, bigint, int)',
        'sync_pull_watch_progress(int, bigint, int)',
        'sync_push_watch_progress(int, jsonb, text)',
        'sync_delete_watch_progress(int, jsonb, text)',
        'sync_get_watched_items_delta_cursor(int)',
        'sync_pull_watched_items_delta(int, bigint, int)',
        'sync_pull_watched_items(int, int, int)',
        'sync_push_watched_items(int, jsonb, text)',
        'sync_delete_watched_items(int, jsonb, text)',
        'sync_pull_library(int, int, int)',
        'sync_push_library(int, jsonb, text)',
        'sync_pull_profile_settings_blob(int, text)',
        'sync_push_profile_settings_blob(int, text, jsonb, text)',
        'sync_pull_home_catalog_settings(int, text)',
        'sync_push_home_catalog_settings(int, text, jsonb, text)',
        'sync_pull_collections(int)',
        'sync_push_collections(int, jsonb, text)',
        'sync_pull_provider_credentials(int)',
        'sync_push_provider_credentials(int, jsonb, text)',
        'sync_delete_provider_credentials(int, text, text)'
    ] loop
        execute format('revoke execute on function public.%s from public, anon', fn);
        execute format('grant execute on function public.%s to authenticated', fn);
    end loop;
end
$$;

revoke execute on function public.get_avatar_catalog() from public;
grant execute on function public.get_avatar_catalog() to anon, authenticated;
revoke execute on function public._sync_invalidate(text, int, text) from public, anon, authenticated;
revoke execute on function public._require_uid() from public, anon;
grant execute on function public._require_uid() to authenticated;
