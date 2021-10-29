/* groovylint-disable LineLength */

void call() {

    properties([
        disableConcurrentBuilds(),
        parameters([
            choice(
                name: 'REPO', choices: ['vega', 'data-node'],
                description: '''Select repo to sync
                |It will sync repos:
                |- vega -> vega-snapshots
                |- data-node -> data-node-snapshots
                '''.stripMargin()),
            string(
                name: 'VERSION_TAG', defaultValue: '',
                /* groovylint-disable-next-line GStringExpressionWithinString */
                description: '''"v0.45.1". The tag from the vega/data-node source repo.
                |The job will create a branch "release/${VERSION_TAG}" in the target -snapshot repo.
                '''.stripMargin()),
        ])
    ])

    echo "params=${params}"

    node {
        skipDefaultCheckout()
        cleanWs()

        timestamps {
            timeout(time: 10, unit: 'MINUTES') {
                stage('Config') {
                    sh 'printenv'
                    echo "params=${params.inspect()}"
                }

                stage("Git clone the Source '${params.REPO}' repo") {
                    dir("${params.REPO}") {
                        checkout([
                            $class: 'GitSCM',
                            branches: [[name: params.VERSION_TAG]],
                            userRemoteConfigs: [[
                                url: "git@github.com:vegaprotocol/${params.REPO}.git",
                                credentialsId: 'vega-ci-bot'
                            ]]])
                    }
                }

                stage("Git clone the Target `${params.REPO}-snapshots` repo") {
                    dir("${params.REPO}-snapshots") {
                        checkout([
                            $class: 'GitSCM',
                            userRemoteConfigs: [[
                                url: "git@github.com:vegaprotocol/${params.REPO}-snapshots.git",
                                credentialsId: 'vega-ci-bot'
                            ]]])
                    }
                }

                stage('Clean the Target repo directory') {
                    dir("${params.REPO}-snapshots") {
                        sh label: 'Clean the Target repo', script: '''#!/bin/bash -e
                            git rm -rf --quiet .
                            git checkout HEAD -- .github
                        '''
                    }
                }

                stage('Copy files') {
                    sh label: 'Sync the files from the Source to the Target repo directory', script: """#!/bin/bash -e
                        rsync -a \
                            ./"${params.REPO}"/. \
                            ./"${params.REPO}-snapshots"/. \
                            --exclude .git \
                            --exclude .github \
                            --exclude .drone-github.yml \
                            --exclude Jenkinsfile
                    """
                }

                stage('Diff') {
                    sh label: 'Diff directories', script: """#!/bin/bash -e
                        diff -rq ./"${params.REPO}" ./"${params.REPO}-snapshots" | grep -v "/\\.git/"
                    """
                }

                stage('Create branch and commit') {
                    dir("${params.REPO}-snapshots") {
                        sh label: 'Create branch and commit changes', script: """#!/bin/bash -e
                            git checkout -b "release/${params.VERSION_TAG}"
                            git add .
                            git commit -m "Release ${VERSION_TAG}"
                        """
                    }
                }

                stage('Git push changes') {
                    dir("${params.REPO}-snapshots") {
                        sh label: 'Git push branch', script: """#!/bin/bash -e
                            git status
                            echo "fetch"
                            git fetch
                            echo "pull"
                            git pull
                        """
                    }
                }

            }
        }
    }
}
