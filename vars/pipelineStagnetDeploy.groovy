/* groovylint-disable DuplicateStringLiteral */
/* groovylint-disable DuplicateNumberLiteral */
/* groovylint-disable MethodSize */
/* groovylint-disable NestedBlockDepth */
/* groovylint-disable GStringAsMapKey */
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

void call() {
    final DATA_NODE_PARAM_DEFAULT = 'network-default'

    properties([
        copyArtifactPermission('*'),
        disableConcurrentBuilds(),
        parameters([
            choice(
                name: 'NETWORK', choices: ['Stagnet 1', 'Stagnet 2'],
                description: 'Select Stagnet Network'),
            text(
                name: 'REASON', defaultValue: pipelineDefaults.stag.reason,
                description: 'In a few words.'),
            string(
                name: 'DEPLOY_VEGA_CORE', defaultValue: pipelineDefaults.stag.deployVegaCore,
                description: '"v0.45.1". Version of Vega Core to deploy; Leave empty for no version change'),
            string(
                name: 'DEPLOY_DATA_NODE', defaultValue: DATA_NODE_PARAM_DEFAULT,
                description: 'Version of the data-node binary. If ' + DATA_NODE_PARAM_DEFAULT + ', the network default will be used'),
            booleanParam(
                name: 'DEPLOY_CONFIG', defaultValue: pipelineDefaults.stag.deployConfig,
                description: 'Deploy some Vega Network config, e.g. genesis file'),
            booleanParam(
                name: 'RESTART_NETWORK', defaultValue: pipelineDefaults.stag.restartNetwork,
                description: 'Restart the Network'),
            string(
                name: 'DEVOPS_INFRA_BRANCH', defaultValue: pipelineDefaults.stag.devopsInfraBranch,
                description: 'Git branch, tag or hash of the vegaprotocol/devops-infra repository'),
            string(
                name: 'ANSIBLE_BRANCH', defaultValue: 'master',
                description: 'Git branch, tag or hash of the vegaprotocol/ansible repository'),
        ])
    ])

    echo "params=${params}"

    node {
        String networkID = 'UNKNOWN'
        if (params.NETWORK == 'Stagnet 1') {
            networkID = 'stagnet'
        } else if (params.NETWORK == 'Stagnet 2') {
            networkID = 'stagnet2'
        } else {
            error("Unsupported network ${params.NETWORK}.")
        }

        // NOTE: environment variables PSSH_USER and PSSH_KEYFILE are used by veganet.sh script
        /* groovylint-disable-next-line NoDef, VariableTypeRequired */
        def sshStagnetCredentials = sshUserPrivateKey(  credentialsId: 'ssh-vega-network',
                                                      keyFileVariable: 'PSSH_KEYFILE',
                                                     usernameVariable: 'PSSH_USER')
        /* groovylint-disable-next-line NoDef, VariableTypeRequired */
        def githubAPICredentials = usernamePassword(credentialsId: 'github-vega-ci-bot-artifacts',
                                                 passwordVariable: 'GITHUB_API_TOKEN',
                                                 usernameVariable: 'GITHUB_API_USER')
        Map dockerCredentials = [credentialsId: 'github-vega-ci-bot-artifacts',
                                           url: 'https://ghcr.io']

        skipDefaultCheckout()
        cleanWs()

        timestamps {
            try {
                timeout(time: 100, unit: 'MINUTES') {
                    stage('Git Clone') {
                        gitClone(params.DEVOPS_INFRA_BRANCH, params.ANSIBLE_BRANCH)
                    }
                    stage('Status') {
                        parallel([
                            'CI config': {
                                // Printout all configuration variables
                                sh 'printenv'
                                echo "params=${params.inspect()}"
                            },
                            "${params.NETWORK}: status": {
                                withDockerRegistry(dockerCredentials) {
                                    withCredentials([sshStagnetCredentials]) {
                                        sh script: "./veganet.sh ${networkID} status"
                                    }
                                }
                            },
                            "${params.NETWORK}: config": {
                                dir('ansible') {
                                    withCredentials([sshStagnetCredentials]) {
                                        script {
                                            ['tendermint', 'vegaserver'].each { playbook ->
                                                sh label: 'ansible deploy run', script: """#!/bin/bash -e
                                                    ansible-playbook \
                                                        --diff \
                                                        -u "\${PSSH_USER}" \
                                                        --private-key "\${PSSH_KEYFILE}" \
                                                        --inventory inventories \
                                                        --limit ${env.NET_NAME} \
                                                        --tags vega-network-config \
                                                        playbooks/playbook-${playbook}.yaml
                                                """
                                            }
                                        }
                                    }
                                }
                            }
                        ])
                    }

                    stage('Approve') {
                        // TODO: Limit who can approve and send public slack message to them
                        timeout(time: 15, unit: 'MINUTES') {
                            input message: """Deploy to "${params.NETWORK}" Network?\n
                            |- Deploy Vega Core: '${params.DEPLOY_VEGA_CORE ?: 'unchanged'}'
                            |- Deploy Data Node: '${params.DEPLOY_DATA_NODE}'
                            |- Deploy Config (genesis etc): '${params.DEPLOY_CONFIG ? 'yes' : 'no'}'
                            |- Restart: '${params.RESTART_NETWORK}'
                            |- Reason: \n"${params.REASON}"
                            |\nNote: You can view potential Network Config changes (e.g. genesis) in previous stage
                            """.stripMargin(), ok: 'Approve'
                        }
                    }
                    String deployStageName = 'Deploy Vega Core binary'
                    stage(deployStageName) {
                        if (params.DEPLOY_VEGA_CORE) {
                            withDockerRegistry(dockerCredentials) {
                                withCredentials([githubAPICredentials]) {
                                    withCredentials([sshStagnetCredentials]) {
                                        sh script: "TAG='${params.DEPLOY_VEGA_CORE}' ./veganet.sh ${networkID} getvega"
                                    }
                                }
                            }
                        } else {
                            echo 'Skip: DEPLOY_VEGA_CORE not specified'
                            Utils.markStageSkippedForConditional(deployStageName)
                        }
                    }
                    String deployConfigStageName = 'Deploy Vega Network Config'
                    stage(deployConfigStageName) {
                        if (params.DEPLOY_CONFIG) {
                            dir('ansible') {
                                withCredentials([sshStagnetCredentials]) {
                                    // Note: environment variables PSSH_KEYFILE and PSSH_USER
                                    //        are set by withCredentials wrapper
                                    script {
                                        ['tendermint', 'vegaserver'].each { playbook ->
                                            sh label: 'ansible deploy run', script: """#!/bin/bash -e
                                                ansible-playbook \
                                                    --diff \
                                                    -u "\${PSSH_USER}" \
                                                    --private-key "\${PSSH_KEYFILE}" \
                                                    --inventory inventories \
                                                    --limit ${env.NET_NAME} \
                                                    --tags vega-network-config \
                                                    playbooks/playbook-${playbook}.yaml
                                            """
                                        }
                                    }
                                }
                            }
                        } else {
                            echo 'Skip: DEPLOY_CONFIG is false'
                            Utils.markStageSkippedForConditional(deployConfigStageName)
                        }
                    }
                    String restartStageName = 'Restart Network'
                    stage(restartStageName) {
                        additionalVars = []
                        if (params.DEPLOY_DATA_NODE != DATA_NODE_PARAM_DEFAULT) {
                            echo 'Deploying the Data Node tag: ' + params.DEPLOY_DATA_NODE
                            additionalVars = ['DATANODE_TAG='+params.DEPLOY_DATA_NODE]
                        }

                        if (params.RESTART_NETWORK) {
                            withDockerRegistry(dockerCredentials) {
                                withCredentials([sshStagnetCredentials]) {
                                    sh script: additionalVars.join(' ') + ' ./veganet.sh ' + networkID + ' bounce'
                                }
                            }
                        } else {
                            echo 'Skip: RESTART_NETWORK is false'
                            Utils.markStageSkippedForConditional(restartStageName)
                        }
                    }
                }
                // Workaround Jenkins problem: https://issues.jenkins.io/browse/JENKINS-47403
                // i.e. `currentResult` is not set properly in the finally block
                // CloudBees workaround: https://support.cloudbees.com/hc/en-us/articles/218554077-how-to-set-current-build-result-in-pipeline
                currentBuild.result = currentBuild.result ?: 'SUCCESS'
                // result can be SUCCESS or UNSTABLE
            } catch (FlowInterruptedException e) {
                currentBuild.result = 'ABORTED'
                throw e
            } catch (e) {
                // Workaround Jenkins problem: https://issues.jenkins.io/browse/JENKINS-47403
                // i.e. `currentResult` is not set properly in the finally block
                // CloudBees workaround: https://support.cloudbees.com/hc/en-us/articles/218554077-how-to-set-current-build-result-in-pipeline
                currentBuild.result = 'FAILURE'
                throw e
            } finally {
                stage('Cleanup') {
                    if (currentBuild.result == 'SUCCESS') {
                        String msg = "Successfully restarted `${params.NETWORK}`"
                        if (params.DEPLOY_VEGA_CORE) {
                            msg = "Successfully deployed `${params.DEPLOY_VEGA_CORE}` to `${params.NETWORK}`"
                        }
                        slackSend(
                            channel: '#tradingcore-notify',
                            color: 'good',
                            message: ":rocket: ${msg} :astronaut:",
                        )
                    } else {
                        String msg = "Failed to restart `${params.NETWORK}`"
                        if (params.DEPLOY_VEGA_CORE) {
                            msg = "Failed to deploy `${params.DEPLOY_VEGA_CORE}` to `${params.NETWORK}`"
                        }
                        msg += ". Please check <${env.RUN_DISPLAY_URL}|CI logs> for details"
                        slackSend(
                            channel: '#tradingcore-notify',
                            color: 'danger',
                            message: ":boom: ${msg} :scream:",
                        )
                    }
                }
            }
        }
    }
}

void gitClone(String devopsInfraBranch, String ansibleBranch) {
    retry(3) {
        checkout([
            $class: 'GitSCM',
            branches: [[name: devopsInfraBranch]],
            userRemoteConfigs: [[
                url: 'git@github.com:vegaprotocol/devops-infra.git',
                credentialsId: 'vega-ci-bot'
            ]]])
    }
    retry(3) {
        dir('ansible') {
            checkout([
                $class: 'GitSCM',
                branches: [[name: ansibleBranch]],
                userRemoteConfigs: [[
                    url: 'git@github.com:vegaprotocol/ansible.git',
                    credentialsId: 'vega-ci-bot'
                ]]])
        }
    }
}
