/**
 * Release naming, in one place.
 *
 * A pre-release is `X.Y.Z-alpha.N`, `X.Y.Z-beta.N` or `X.Y.Z-rc.N`; a stable is
 * `X.Y.Z`. N is the only number that moves inside one X.Y.Z and it stops at
 * [MAX_PRE_RELEASE_BUILD], so a line cannot silently grow past beta.9 or reuse the
 * tag shape of a version that already shipped. After 2.1.0 a fix is a PATCH, a
 * backward-compatible feature is a MINOR and a breaking change is a MAJOR — each
 * a new X.Y.Z, never another pre-release build of a released version. See
 * RELEASE.md and scripts/release_guard.py, which enforce the same rules.
 */
sealed class Version(val major: Int, val minor: Int, val patch: Int, val build: Int = 0) {
    abstract val name: String
    abstract val code: Long

    class Alpha(versionMajor: Int, versionMinor: Int, versionPatch: Int, versionBuild: Int) :
        Version(versionMajor, versionMinor, versionPatch, versionBuild) {
        init {
            require(versionBuild in 1..MAX_PRE_RELEASE_BUILD) {
                "alpha build must be 1..$MAX_PRE_RELEASE_BUILD, was $versionBuild"
            }
        }

        override val name: String
            get() = "${major}.${minor}.${patch}-alpha.$build"

        override val code: Long
            get() = major * MAJOR + minor * MINOR + patch * PATCH + build * BUILD + ALPHA
    }

    class Beta(versionMajor: Int, versionMinor: Int, versionPatch: Int, versionBuild: Int) :
        Version(versionMajor, versionMinor, versionPatch, versionBuild) {
        init {
            require(versionBuild in 1..MAX_PRE_RELEASE_BUILD) {
                "beta build must be 1..$MAX_PRE_RELEASE_BUILD, was $versionBuild"
            }
        }

        override val name: String
            get() = "${major}.${minor}.${patch}-beta.$build"

        override val code: Long
            get() = major * MAJOR + minor * MINOR + patch * PATCH + build * BUILD + BETA
    }

    class Stable(versionMajor: Int, versionMinor: Int, versionPatch: Int) :
        Version(versionMajor, versionMinor, versionPatch) {
        override val name: String
            get() = "${major}.${minor}.${patch}"

        override val code: Long
            get() = major * MAJOR + minor * MINOR + patch * PATCH + build * BUILD + STABLE
    }

    class ReleaseCandidate(
        versionMajor: Int,
        versionMinor: Int,
        versionPatch: Int,
        versionBuild: Int,
    ) : Version(versionMajor, versionMinor, versionPatch, versionBuild) {
        init {
            require(versionBuild in 1..MAX_PRE_RELEASE_BUILD) {
                "release-candidate build must be 1..$MAX_PRE_RELEASE_BUILD, was $versionBuild"
            }
        }

        override val name: String
            get() = "${major}.${minor}.${patch}-rc.$build"

        override val code: Long
            get() =
                major * MAJOR + minor * MINOR + patch * PATCH + build * BUILD + RELEASE_CANDIDATE
    }
}

// private const val ABI = 1L
private const val BUILD = 10L

/** Highest build number a pre-release line may use: the beta.9 hard cap. */
private const val MAX_PRE_RELEASE_BUILD = 9

private const val VARIANT = 100L
private const val PATCH = 10_000L
private const val MINOR = 1_000_000L
private const val MAJOR = 100_000_000L

private const val STABLE = VARIANT * 4
private const val ALPHA = VARIANT * 1
private const val BETA = VARIANT * 2
private const val RELEASE_CANDIDATE = VARIANT * 3

val currentVersion: Version =
    Version.ReleaseCandidate(versionMajor = 2, versionMinor = 1, versionPatch = 0, versionBuild = 1)
