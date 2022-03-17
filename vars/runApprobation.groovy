import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

void call(Map config = [:]) {
    String approbationJob = '/system-tests/approbation'
    Boolean ignoreFailure = config.ignoreFailure ? "${config.ignoreFailure}".toBoolean() : false
    List buildParameters = [
            // Different repos branches
            string(name: 'VEGA_CORE_BRANCH', value: config.vegaCore ?: pipelineDefaults.appr.vegaCoreBranch),
            string(name: 'SPECS_INTERNAL_BRANCH', value: config.specsInternal ?: pipelineDefaults.appr.specsInternalBranch),
            string(name: 'MULTISIG_CONTROL_BRANCH', value: config.multisigControl ?: pipelineDefaults.appr.multisigControlBranch),
            string(name: 'SYSTEM_TESTS_BRANCH', value: config.systemTests ?: pipelineDefaults.appr.systemTestsBranch),

            // Approgation arguments
            string(name: 'SPECS_ARG', value: config.specsArg ?: pipelineDefaults.appr.specsArg),
            string(name: 'TESTS_ARG', value: config.testsArg ?: pipelineDefaults.appr.testsArg),
            string(name: 'IGNORE_ARG', value: config.ignoreArg ?: pipelineDefaults.appr.ignoreArg),
            string(name: 'OTHER_ARG', value: config.otherArg ?: pipelineDefaults.appr.otherArg),

            // Approbation version
            string(name: 'APPROBATION_VERSION', value: config.approbation ?: pipelineDefaults.appr.approbationVersion),
        ]

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
