import { createClient } from '@supabase/supabase-js';

// Publishable key is intended for browser/mobile clients. Never put service_role here.
export const SUPABASE_URL = 'https://beasxxexggnjcocdbtdl.supabase.co';
export const SUPABASE_PUBLISHABLE_KEY = 'sb_publishable_85hufMmFQvmLQt2Rz1Hcrw_S4t7BjAO';

export const supabase = createClient(SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY, {
  auth: { persistSession: true, autoRefreshToken: true, detectSessionInUrl: true },
});
