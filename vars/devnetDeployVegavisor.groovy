/* groovylint-disable DuplicateStringLiteral */
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

void call(Map config = [:]) {
    String devnet2DeployJob = '/private/Deployments/Vegavisor/Devnet 2'
    Boolean wait = config.containsKey("wait") ? "${config.wait}".toBoolean() : false
    Boolean ignoreFailure = config.containsKey("ignoreFailure") ? "${config.ignoreFailure}".toBoolean() : false

    List buildParameters = [
            string(
                name: 'VEGA_VERSION',
                value: config.vegaVersion ?: '',
            ),
            booleanParam(
                name: 'REGENERATE_CONFIG',
                value: config.containsKey("regenerateConfig") ? "${config.regenerateConfig}".toBoolean() : true,
            ),
            string(
                name: 'VEGACAPSULE_BRANCH',
                value: config.vegacapsuleBranch ?: '',
            ),
            string(
                name: 'DEVOPSSCRIPTS_BRANCH',
                value: config.devopsscriptsBranch ?: '',
            ),
            string(
                name: 'ANSIBLE_BRANCH',
                value: config.ansibleBranch ?: '',
            ),
            string(
                name: 'NETWORKS_INTERNAL',
                value: config.devopsscriptsBranch ?: '',
            ),
            string(
                name: 'JENKINS_SHARED_LIB_BRANCH',
                value: config.ansibleBranch ?: '',
            ),
            booleanParam(
                name: 'CREATE_MARKETS',
                value: config.containsKey("createMarkets") ? "${config.createMarkets}".toBoolean() : true
            ),
            booleanParam(
                name: 'CREATE_INCENTIVE_MARKETS',
                value: config.containsKey("createIncentiveMarkets") ? "${config.createIncentiveMarkets}".toBoolean() : false
            ),
            booleanParam(
                name: 'BOUNCE_BOTS',
                value: config.containsKey("bounceBots") ? "${config.bounceBots}".toBoolean() : true
            ),
            booleanParam(
                name: 'REMOVE_WALLETS',
                value: config.containsKey("removeWallets") ? "${config.removeWallets}".toBoolean() : false
            ),
        ]

    echo 'Deploy to Devnet 2 network'

    RunWrapper deployJob = build(
        job: devnet2DeployJob,
        propagate: false,  // don't fail yet
        wait: wait,
        parameters: buildParameters
    )

    echo "Deploy to Devnet 2 pipeline: ${wait ? deployJob.absoluteUrl : ''}"

    // now fail
    if (wait && deployJob.result != 'SUCCESS') {
        if (ignoreFailure) {
            // workaround to:
            // - change status of current stage to not successful
            // - don't change build status, keep it as it was outside of this stage
            catchError(message: 'Deploy to Devnet 2 Failed', buildResult: null, stageResult: deployJob.result) {
                error("""Downstream 'Deploy to Devnet 2' pipeline failed, click for details: ${deployJob.absoluteUrl}.
                Ignore failure and keep job green, but mark stage as ${deployJob.result}""")
            }
        } else {
            if (deployJob.result == 'UNSTABLE') {
                unstable("""UNSTABLE - Downstream 'Deploy to Devnet 2' pipeline failed.
                    Click for details: ${deployJob.absoluteUrl}""")
            } else if (deployJob.result == 'ABORTED') {
                currentBuild.result = 'ABORTED'
                error("""ABORTED - Downstream 'Deploy to Devnet 2' pipeline failed.
                    Click for details: ${deployJob.absoluteUrl}""")
            } else {
                error("""${deployJob.result} - Downstream 'Deploy to Devnet 2' pipeline failed.
                    Click for details: ${deployJob.absoluteUrl}""")
            }
        }
    }
}
