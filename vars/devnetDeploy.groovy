/* groovylint-disable DuplicateStringLiteral */
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

void call(Map config = [:]) {
    String devnetDeployJob = 'cd/Devnet deploy'
    Boolean ignoreFailure = config.ignoreFailure ? "${config.ignoreFailure}".toBoolean() : false

    echo 'Deploy to Devnet network'

    RunWrapper deployJob = build(
        job: devnetDeployJob,
        propagate: false,  // don't fail yet
        wait: true
    )

    echo "Deploy to Devnet pipeline: ${deployJob.absoluteUrl}"

    // now fail
    if (deployJob.result != 'SUCCESS') {
        if (ignoreFailure) {
            // workaround to:
            // - change status of current stage to not successful
            // - don't change build status, keep it as it was outside of this stage
            catchError(message: 'Deploy to Devnet Failed', buildResult: null, stageResult: deployJob.result) {
                error("Ignore failure and keep job green, but mark stage as ${deployJob.result}")
            }
        } else {
            if (deployJob.result == 'UNSTABLE') {
                unstable('UNSTABLE - Deploy to Devnet')
            } else if (deployJob.result == 'ABORTED') {
                currentBuild.result = 'ABORTED'
                error('ABORTED - Deploy to Devnet')
            } else {
                error("${deployJob.result} - Deploy to Devnet")
            }
        }
    }
}
