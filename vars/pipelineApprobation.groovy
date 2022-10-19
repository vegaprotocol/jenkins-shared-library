/* groovylint-disable DuplicateStringLiteral */
/* groovylint-disable DuplicateNumberLiteral */
/* groovylint-disable MethodSize */
/* groovylint-disable NestedBlockDepth */
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

void call(def config=[:]) {

    String scriptSlackMsg = ''
    echo "buildCauses=${currentBuild.buildCauses}"
    if (currentBuild.upstreamBuilds) {
        RunWrapper upBuild = currentBuild.upstreamBuilds[0]
        currentBuild.displayName = "#${currentBuild.id} - ${upBuild.fullProjectName} #${upBuild.id}"
    }
    echo "params=${params}"

    pipeline {
        agent any
        options {
            skipDefaultCheckout()
            timestamps()
            timeout(time: 20, unit: 'MINUTES')
        }
        stages {
            stage('CI Config') {
                steps {
                    script {
                        sh 'printenv'
                    }
                    echo "params=${params.inspect()}"
                }
            }
            stage('Git Clone') {
                parallel {
                    stage('vega core') {
                        when {
                            expression {
                                config.type == 'core'
                            }
                        }
                        steps {
                            gitClone(
                                directory: 'vega',
                                githubUrl: params.ORIGIN_REPO,
                                branch: "*/${params.VEGA_CORE_BRANCH}",
                                extensions: [[$class: 'LocalBranch', localBranch: params.VEGA_CORE_BRANCH ]]
                            )
                        }
                    }
                    stage('specs') {
                        steps {
                            gitClone(
                                directory: 'specs',
                                vegaUrl: 'specs',
                                branch: "*/${params.SPECS_BRANCH}",
                                extensions: [[$class: 'LocalBranch', localBranch: params.SPECS_BRANCH ]]
                            )
                        }
                    }
                    stage('MultisigControl') {
                        when {
                            expression {
                                config.type == 'core'
                            }
                        }
                        steps {
                            gitClone(
                                directory: 'MultisigControl',
                                vegaUrl: 'MultisigControl',
                                branch: "*/${params.MULTISIG_CONTROL_BRANCH}",
                                extensions: [[$class: 'LocalBranch', localBranch: params.MULTISIG_CONTROL_BRANCH ]]
                            )
                        }
                    }
                    stage('system-tests') {
                        when {
                            expression {
                                config.type == 'core'
                            }
                        }
                        steps {
                            gitClone(
                                directory: 'system-tests',
                                vegaUrl: 'system-tests',
                                branch: "*/${params.SYSTEM_TESTS_BRANCH}",
                                extensions: [[$class: 'LocalBranch', localBranch: params.SYSTEM_TESTS_BRANCH ]]
                            )
                        }
                    }
                    stage('frontend-monorepo') {
                        when {
                            expression {
                                config.type == 'frontend'
                            }
                        }
                        steps {
                            gitClone(
                                directory: 'frontend-monorepo',
                                vegaUrl: 'frontend-monorepo',
                                branch: "*/${params.FRONTEND_BRANCH}",
                                extensions: [[$class: 'LocalBranch', localBranch: params.FRONTEND_BRANCH ]]
                            )
                        }
                    }
                    stage('vegawallet-desktop') {
                        when {
                            expression {
                                config.type == 'frontend'
                            }
                        }
                        steps {
                            gitClone(
                                directory: 'vegawallet-desktop',
                                vegaUrl: 'vegawallet-desktop',
                                branch: "*/${params.VEGAWALLET_DESKTOP_BRANCH}",
                                extensions: [[$class: 'LocalBranch', localBranch: params.VEGAWALLET_DESKTOP_BRANCH ]]
                            )
                        }
                    }
                }
            }
            stage('Run Approbation') {
                steps {
                    sh label: 'approbation', script: """#!/bin/bash -e
                        npx @vegaprotocol/approbation@${params.APPROBATION_VERSION} check-references \
                            --specs="${params.SPECS_ARG}" \
                            --tests="${params.TESTS_ARG}" \
                            ${params.IGNORE_ARG ? "--ignore=${params.IGNORE_ARG}" : '' } ${params.OTHER_ARG}
                    """
                }
            }
        }
        post {
            always {
                catchError {
                    archiveArtifacts artifacts: 'results/*',
                        allowEmptyArchive: true
                }
                script {
                    scriptSlackMsg = sh(
                        script: "cat results/jenkins.txt || echo 'no jenkins.txt'",
                        returnStdout: true,
                    ).trim()
                    sendSlackMessage(scriptSlackMsg)
                }
                cleanWs()
            }
        }
    }
}


void sendSlackMessage(String scriptMsg) {
    String slackChannel = '#coverage-notify'
    String jobURL = env.RUN_DISPLAY_URL
    String jobName = currentBuild.displayName

    String currentResult = currentBuild.result ?: currentBuild.currentResult
    String duration = currentBuild.durationString - ' and counting'
    String msg = ''
    String color = ''

    if (currentResult == 'SUCCESS') {
        msg = ":large_green_circle: Approbation <${jobURL}|${jobName}>"
        color = 'good'
    } else if (currentResult == 'ABORTED') {
        msg = ":black_circle: Approbation aborted <${jobURL}|${jobName}>"
        color = '#000000'
    } else {
        msg = ":red_circle: Approbation <${jobURL}|${jobName}>"
        color = 'danger'
    }

    msg += " (${duration})"

    if (scriptMsg != '') {
        msg += "\n${scriptMsg}"
    }

    slackSend(
        channel: slackChannel,
        color: color,
        message: msg,
    )
}
