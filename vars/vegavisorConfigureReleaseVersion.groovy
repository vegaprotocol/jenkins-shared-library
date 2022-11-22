def call () {
    DOCKER_VERSION = params.DOCKER_VERSION
    if (params.RELEASE_VERSION) {
        RELEASE_VERSION = params.RELEASE_VERSION
    }
    if (RELEASE_VERSION == 'latest') {
        if (env.NET_NAME == 'testnet') {
            error "Do not deploy latest on testnet, instead of that provide manually RELEASE_VERSION from https://github.com/vegaprotocol/vega/releases"
        }
        echo 'Using latest version for RELEASE_VERSION'
        // change to param if needed for other envs
        def RELEASE_REPO = 'vegaprotocol/vega-dev-releases'
        withGHCLI() {
            RELEASE_VERSION = sh(
                script: "gh release list --repo ${RELEASE_REPO} --limit 1 | awk '{print \$1}'",
                returnStdout: true
            ).trim()
        }
        if (!RELEASE_VERSION) {
            error "Couldn't fetch release version, stopping pipeline!"
        }
        // use commit hash from release to set correct DOCKER_VERSION
        if (!params.DOCKER_VERSION) {
            DOCKER_VERSION = RELEASE_VERSION.split('-').last()
        }
    }
    if (RELEASE_VERSION) {
        if (currentBuild.description) {
            currentBuild.description += ", release version: ${RELEASE_VERSION}"
        }
        else {
            currentBuild.description =  "release version: ${RELEASE_VERSION}"
        }
    }

}