package com.openminis.app.data

/**
 * Pure mapping from a skill's declared env vars + the currently configured
 * env vars to an integration tier (0 / 1 / 2), mirroring RikkaMinis'
 * `determineIntegrationTier`.
 *
 * Kept dependency-free (no Android imports) so it can be unit-tested
 * standalone. Consumed by ChatViewModel.buildIntegrationStatus() to tell
 * the model which bundled platform skills it can actually use right now.
 *
 * Tier contract:
 *  - 0: no declared env requirement, or none of the declared vars configured
 *  - 1: partial subset of declared vars configured (read-only / limited)
 *  - 2: every declared var configured (full capability)
 */
object SkillIntegrationTier {

    /**
     * [declared] is the set of env var names a skill's requirements.json
     * declares (e.g. `GITHUB_TOKEN`). [configured] is the set of env var
     * names present in the app's env store with a non-blank value.
     */
    fun resolve(declared: Set<String>, configured: Set<String>): Int {
        if (declared.isEmpty()) return 0
        val found = declared.count { it in configured }
        return when {
            found == declared.size -> 2
            found > 0 -> 1
            else -> 0
        }
    }
}
