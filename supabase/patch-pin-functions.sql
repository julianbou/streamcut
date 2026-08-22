-- Patch: fix PIN functions (pgcrypto lives in the 'extensions' schema on Supabase).
-- Run this in the SQL editor. Safe to re-run.

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
