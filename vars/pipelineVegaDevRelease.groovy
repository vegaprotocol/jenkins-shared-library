void call() {
    // NOTE: environment variables PSSH_USER and PSSH_KEYFILE are used by veganet.sh script
    /* groovylint-disable-next-line NoDef, VariableTypeRequired */
    def sshCredentials = sshUserPrivateKey(
        credentialsId: 'ssh-vega-network',
        keyFileVariable: 'PSSH_KEYFILE',
        usernameVariable: 'PSSH_USER'
    )
    Map dockerCredentials = [
        credentialsId: 'github-vega-ci-bot-artifacts',
        url: 'https://ghcr.io'
    ]
    def githubAPICredentials = usernamePassword(
        credentialsId: 'github-vega-ci-bot-artifacts',
        passwordVariable: 'GITHUB_API_TOKEN',
        usernameVariable: 'GITHUB_API_USER'
    )

    def doGitClone = { repo, branch ->
        dir(repo) {
            retry(3) {
                // returns object:
                // [GIT_BRANCH:origin/master,
                // GIT_COMMIT:5897d0e927e920fc217f967e91ea086f8cf2bb41,
                // GIT_PREVIOUS_COMMIT:5897d0e927e920fc217f967e91ea086f8cf2bb41,
                // GIT_PREVIOUS_SUCCESSFUL_COMMIT:5897d0e927e920fc217f967e91ea086f8cf2bb41, 
                // GIT_URL:git@github.com:vegaprotocol/devops-infra.git]
                return checkout([
                    $class: 'GitSCM',
                    branches: [[name: branch]],
                    userRemoteConfigs: [[
                        url: "git@github.com:vegaprotocol/${repo}.git",
                        credentialsId: 'vega-ci-bot'
                    ]]])
            }
        }
    }

    def versionTag = 'UNKNOWN'

    pipeline {
        agent any
        options {
            skipDefaultCheckout()
            timeout(time: 40, unit: 'MINUTES')
            timestamps()
        }
        environment {
            PATH = "${env.WORKSPACE}/bin:${env.PATH}"
        }
        stages {
            stage('CI Config') {
                steps {
                    sh "printenv"
                    echo "params=${params.inspect()}"
                }
            }
            //
            // Begin Git CLONE
            //
            stage('Checkout') {
                parallel {
                    stage('vega'){
                        when {
                            expression { params.VEGA_VERSION }
                        }
                        steps {
                            script {
                                doGitClone('vega', params.VEGA_VERSION)
                            }
                            // add commit hash to version
                            dir('vega') {
                                script {
                                    // counter can duplicate
                                    def counter = sh(
                                        script: "git rev-list --no-merges --count HEAD",
                                        returnStdout: true,
                                    ).trim()
                                    def versionHash = sh(
                                        script: "git rev-parse --short HEAD",
                                        returnStdout: true,
                                    ).trim()
                                    def orgVersion = sh(
                                        script: "grep -o '\"v0.*\"' version/version.go",
                                        returnStdout: true,
                                    ).trim()
                                    orgVersion = orgVersion.replace('"', '')
                                    versionTag = orgVersion + '-' + counter + '-' + versionHash
                                }
                                sh label: 'Add hash to version', script: """#!/bin/bash -e
                                    sed -i 's/"v0.*"/"${versionTag}"/g' version/version.go
                                """
                                print('Binary version ' + versionTag)
                            }
                        }
                    }
                }
            }
            //
            // End Git CLONE
            //
            //
            // Begin COMPILE & ZIP
            //
            stage('Compile and zip') {
                matrix {
                    axes {
                        axis {
                            name 'GOOS'
                            values 'linux', 'darwin'
                        }
                        axis {
                            name 'GOARCH'
                            values 'amd64', 'arm64'
                        }
                    }
                    stages {
                        stage('Build') {
                            environment {
                                GOOS         = "${GOOS}"
                                GOARCH       = "${GOARCH}"
                            }
                            options { retry(3) }
                            steps {
                                sh 'printenv'
                                dir('vega') {
                                    sh label: 'Compile', script: """#!/bin/bash -e
                                        go build -v \
                                            -o ../build-${GOOS}-${GOARCH}/ \
                                            ./cmd/vega \
                                            ./cmd/data-node \
                                            ./cmd/visor
                                    """
                                    sh label: 'check for modifications', script: 'git diff'
                                }
                                dir("build-${GOOS}-${GOARCH}") {
                                    sh label: 'list files', script: '''#!/bin/bash -e
                                        pwd
                                        ls -lah
                                    '''
                                    sh label: 'Sanity check', script: '''#!/bin/bash -e
                                        file *
                                    '''
                                    script {
                                        if ( GOOS == "linux" && GOARCH == "amd64" ) {
                                            sh label: 'get version', script: '''#!/bin/bash -e
                                                ./vega version
                                                ./data-node version
                                                ./visor --help
                                            '''
                                        }
                                    }
                                }
                            }
                        }
                        stage("zip") {
                            environment {
                                GOOS         = "${GOOS}"
                                GOARCH       = "${GOARCH}"
                            }
                            steps {
                                sh label: 'create release directory', script: """#!/bin/bash -e
                                    mkdir -p ./release
                                """
                                dir("build-${GOOS}-${GOARCH}") {
                                    sh label: 'zip binaries', script: """#!/bin/bash -e
                                        zip ../release/vega-${GOOS}-${GOARCH}.zip ./vega
                                        zip ../release/data-node-${GOOS}-${GOARCH}.zip ./data-node
                                        zip ../release/visor-${GOOS}-${GOARCH}.zip ./visor
                                    """
                                }
                            }
                        }
                    }
                }
            }
            //
            // End COMPILE & ZIP
            //
            //
            // Begin PUBLISH
            //
            stage('Publish to GitHub vega-dev-releases') {
                environment {
                    TAG_NAME = "${versionTag}"
                }
                steps {
                    script {
                        withGHCLI('credentialsId': 'github-vega-ci-bot-artifacts') {
                            sh label: 'Upload artifacts', script: """#!/bin/bash -e
                                gh release view $TAG_NAME --repo vegaprotocol/vega-dev-releases \
                                && gh release upload $TAG_NAME ../release/* --repo vegaprotocol/vega-dev-releases \
                                || gh release create $TAG_NAME ./release/* --repo vegaprotocol/vega-dev-releases
                            """
                        }
                    }
                }
            }
            //
            // End PUBLISH
            //
            //
            // Begin DEPLOY
            //
            stage('Deploy') {
                parallel {
                    stage('Devnet 3'){
                        when {
                            expression { params.DEPLOY_TO_DEVNET_3 }
                        }
                        steps {
                            script {
                                build(
                                    job: 'private/Deployments/Devnet-3/Restart-Network',
                                    propagate: false,
                                    wait: false,
                                    parameters: [
                                        string(name: 'RELEASE_VERSION', value: versionTag),
                                        string(name: 'JENKINS_SHARED_LIB_BRANCH', value: params.JENKINS_SHARED_LIB_BRANCH),
                                    ]
                                )
                            }
                        }
                    }
                }
            }
            //
            // End Deploy
            //
        }
        post {
            always {
                cleanWs()
            }
        }
    }
}
