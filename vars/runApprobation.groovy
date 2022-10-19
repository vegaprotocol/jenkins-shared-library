import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

void call(Map config = [:]) {
    String approbationJob = '/common/approbation'
    if (config.type == 'frontend') {
        approbationJob += '-frontend'
    }
    Boolean ignoreFailure = config.ignoreFailure ? "${config.ignoreFailure}".toBoolean() : false
    List buildParameters = []
    // overrides for default values from upstream pipelines
    if (config.originRepo) {
        buildParameters.add(string(name: 'ORIGIN_REPO', value: config.originRepo))
    }
    if (config.vegaVersion) {
        buildParameters.add(string(name: 'VEGA_BRANCH', value: config.vegaVersion))
    }
    if (config.systemTests) {
        buildParameters.add(string(name: 'SYSTEM_TESTS_BRANCH', value: config.systemTests))
    }
    if (config.specsInternal) {
        buildParameters.add(string(name: 'SPECS_INTERNAL_BRANCH', value: config.specsInternal))
    }
    if (config.frontendBranch) {
        buildParameters.add(string(name: 'FRONTEND_BRANCH', value: config.frontendBranch))
    }
    if (config.vegawalletDesktopBranch) {
        buildParameters.add(string(name: 'VEGAWALLET_DESKTOP_BRANCH', value: config.vegawalletDesktopBranch))
    }


    echo "Starting Approbation with parameters: ${buildParameters}"

    RunWrapper st = build(
        job: approbationJob,
        propagate: false,  // don't fail yet
        wait: true,
        parameters: buildParameters
    )

    // If required copy artifacts from downstream pipeline

    // now fail
    if (st.result != 'SUCCESS') {
        if (ignoreFailure) {
            // workaround to:
            // - change status of current stage to not successful
            // - don't change build status, keep it as it was outside of this stage
            catchError(message: 'Approbation Failed', buildResult: null, stageResult: st.result) {
                error("Ignore failure and keep job green, but mark stage as ${st.result}")
            }
        } else {
            if (st.result == 'UNSTABLE') {
                unstable('UNSTABLE - Approbation')
            } else if (st.result == 'ABORTED') {
                currentBuild.result = 'ABORTED'
                error('ABORTED - Approbation')
            } else {
                error("${st.result} - Approbation")
            }
        }
    }
}
