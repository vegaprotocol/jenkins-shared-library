/* groovylint-disable DuplicateStringLiteral */
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper
import io.vegaprotocol.DockerisedVega

void call() {
    echo "buildCauses=${currentBuild.buildCauses}"
    if (currentBuild.upstreamBuilds) {
        RunWrapper upBuild = currentBuild.upstreamBuilds[0]
        currentBuild.displayName = "#${currentBuild.id} - ${upBuild.fullProjectName} #${upBuild.id}"
    }
    pipelineDockerisedVega.call([
        parameters: [
            string(
                name: 'BEEHIVE_BRANCH', defaultValue: pipelineDefaults.bh.beehiveBranch,
                description: 'Git branch, tag or hash of the vegaprotocol/beehive repository'),
        ],
        git: [
            [name: 'beehive', branch: 'BEEHIVE_BRANCH'],
        ],
        prepareStages: [
            'bh': { Map vars ->
                DockerisedVega dockerisedVega = vars.dockerisedVega
                withEnv([
                    "BEEHIVE_TESTS_DOCKER_IMAGE_TAG=${dockerisedVega.prefix}",
                ]) {
                    stage('build beehive docker image') {
                        dir('beehive') {
                            withDockerRegistry(vars.dockerCredentials) {
                                sh label: 'build beehive container', script: '''#!/bin/bash -e
                                    make build-beehive-docker-image
                                '''
                            }
                        }
                    }
                }
            }
        ],
        mainStage: { Map vars ->
            DockerisedVega dockerisedVega = vars.dockerisedVega
            withEnv([
                "BEEHIVE_TESTS_DOCKER_IMAGE_TAG=${dockerisedVega.prefix}",
                "VEGAWALLET_PATH_TO_BINARY=${env.WORKSPACE}/beehive/vegawallet-binary",
            ]) {
                stage('Check setup') {
                    sh 'printenv'
                    echo "vars=${vars.inspect()}"
                }
                stage('get vegawallet binary') {
                    sh label: 'get vegawallet binary from docker image', script: """#!/bin/bash -e
                        docker cp <containerId>:/usr/local/bin//vegawallet ${VEGAWALLET_PATH_TO_BINARY}
                    """
                }
                stage('run beehive vegawallet tests') {
                    try {
                        dir('beehive') {
                            sh label: 'run beehive vegawallet tests', script: '''#!/bin/bash -e
                                make run-vegawallet-tests-docker
                            '''
                        }
                    } finally {
                        echo 'archive some stuff?'
                    }
                }
            }
        },
        post: {
            echo 'TODO: enable slack messaging'
            // slack.slackSendCIStatus name: 'beehive',
            //     channel: '#qa-notify',
            //     branch: currentBuild.displayName
        }
    ])
}
