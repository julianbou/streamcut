-- Patch: add the missing sync_seed_provider_credentials RPC.
-- Without it every full sync fails its ProviderCredentials step with PGRST202
-- ("Could not find the function ... in the schema cache"), so Trakt, debrid,
-- TMDB and MDBList credentials never reach the account.
-- Run this in the SQL editor. Safe to re-run.

create or replace function public.sync_seed_provider_credentials(
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
    on conflict (user_id, profile_id, provider) do nothing;
end;
$$;

revoke execute on function public.sync_seed_provider_credentials(int, jsonb, text) from public, anon;
grant execute on function public.sync_seed_provider_credentials(int, jsonb, text) to authenticated;
