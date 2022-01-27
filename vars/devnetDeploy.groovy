/* groovylint-disable DuplicateStringLiteral */
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

void call(Map config = [:]) {
    String devnetDeployJob = '/cd/Devnet deploy'
    Boolean wait = config.wait ? "${config.wait}".toBoolean() : false
    Boolean ignoreFailure = config.ignoreFailure ? "${config.ignoreFailure}".toBoolean() : false
    String restart = pipelineDefaults.dev.restart
    if (config.restart == true) {
        restart = pipelineDefaults.restartOptions.restartOnly
    } else if (config.restart == false) {
        restart = pipelineDefaults.restartOptions.dontRestart
    } else if (config.restart == 'checkpoint' || config.checkpoint == true) {
        restart = pipelineDefaults.restartOptions.restartFromCheckpoint
    } else if (config.restart) {
        // not empty, but different than: true, false and 'checkpoint'
        error("Wrong restart argument value: '${config.restart}'. Only: true, false and 'checkpoint' are allowed")
    }
    List buildParameters = [
            string(name: 'VEGA_CORE_VERSION', value: config.vegaCore ?: pipelineDefaults.dev.vegaCoreVersion),
            booleanParam(
                name: 'DEPLOY_CONFIG',
                value: config.deployConfig ? "${config.deployConfig}".toBoolean() : pipelineDefaults.dev.deployConfig
            ),
            string(name: 'RESTART', value: restart),
            booleanParam(
                name: 'CREATE_MARKETS',
                value: config.createMarkets ? "${config.createMarkets}".toBoolean() : pipelineDefaults.dev.createMarkets
            ),
            booleanParam(
                name: 'BOUNCE_BOTS',
                value: config.bounceBots ? "${config.bounceBots}".toBoolean() : pipelineDefaults.dev.bounceBots
            ),
            string(name: 'DEVOPS_INFRA_BRANCH', value: config.devopsInfra ?: pipelineDefaults.dev.devopsInfraBranch),
        ]

    echo 'Deploy to Devnet network'

    RunWrapper deployJob = build(
        job: devnetDeployJob,
        propagate: false,  // don't fail yet
        wait: wait,
        parameters: buildParameters
    )

    echo "Deploy to Devnet pipeline: ${deployJob.absoluteUrl}"

    // now fail
    if (wait && deployJob.result != 'SUCCESS') {
        if (ignoreFailure) {
            // workaround to:
            // - change status of current stage to not successful
            // - don't change build status, keep it as it was outside of this stage
            catchError(message: 'Deploy to Devnet Failed', buildResult: null, stageResult: deployJob.result) {
                error("""Downstream 'Deploy to Devnet' pipeline failed, click for details: ${deployJob.absoluteUrl}.
                Ignore failure and keep job green, but mark stage as ${deployJob.result}""")
            }
        } else {
            if (deployJob.result == 'UNSTABLE') {
                unstable("""UNSTABLE - Downstream 'Deploy to Devnet' pipeline failed.
                    Click for details: ${deployJob.absoluteUrl}""")
            } else if (deployJob.result == 'ABORTED') {
                currentBuild.result = 'ABORTED'
                error("""ABORTED - Downstream 'Deploy to Devnet' pipeline failed.
                    Click for details: ${deployJob.absoluteUrl}""")
            } else {
                error("""${deployJob.result} - Downstream 'Deploy to Devnet' pipeline failed.
                    Click for details: ${deployJob.absoluteUrl}""")
            }
        }
    }
}
