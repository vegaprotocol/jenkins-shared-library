def call (releaseVersion, dockerVersion) {
    if (releaseVersion == 'latest') {
        if (env.NET_NAME == 'testnet') {
            error "Do not deploy latest on testnet, instead of that provide manually RELEASE_VERSION from https://github.com/vegaprotocol/vega/releases"
        }
        echo 'Using latest version for RELEASE_VERSION'
        // change to param if needed for other envs
        def RELEASE_REPO = 'vegaprotocol/vega-dev-releases'
        withGHCLI([:]) {
            releaseVersion = sh(
                script: "gh release list --repo ${RELEASE_REPO} --limit 1 | awk '{print \$1}'",
                returnStdout: true
            ).trim()
        }
        if (!releaseVersion) {
            error "Couldn't fetch release version, stopping pipeline!"
        }
        // use commit hash from release to set correct dockerVersion
        if (!dockerVersion) {
            dockerVersion = releaseVersion.split('-').last()
        }
    }
    if (releaseVersion) {
        if (currentBuild.description) {
            currentBuild.description += ", release version: ${releaseVersion}"
        }
        else {
            currentBuild.description =  "release version: ${releaseVersion}"
        }
    }
    return [releaseVersion, dockerVersion]

}