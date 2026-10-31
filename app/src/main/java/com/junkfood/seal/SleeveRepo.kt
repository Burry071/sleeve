package com.junkfood.seal

/**
 * Sleeve's own repository — the single place to point in-app links and
 * update checks at. Set this to the fork's public GitHub/GitLab URL once
 * the repo is pushed; CI under .github/workflows and .gitlab-ci.yml builds
 * every push automatically.
 */
object SleeveRepo {
    const val REPO_URL: String = "https://github.com/Burry071/sleeve"
    const val RELEASES_URL: String = "$REPO_URL/releases"
    const val ISSUES_URL: String = "$REPO_URL/issues"
}
