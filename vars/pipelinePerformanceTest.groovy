def call() {
    pipeline {
        agent {
            label params.NODE_LABEL
        }
        environment {
            GOBIN = "${env.PWD}/bin"
            PERFHOME = "${env.WORKSPACE}/performance"
            PATH = "${env.PATH}:${env.PERFHOME}/bin"
        }
        stages {
            stage('get source codes') {
                steps {
                    script {
                        def repositories = [
                            [ name: 'vegaprotocol/vega', branch: params.VEGA_BRANCH, install: true ],
                            [ name: 'vegaprotocol/vegacapsule', branch: params.VEGACAPSULE_BRANCH, install: true ],
                            [ name: 'vegaprotocol/vegatools', branch: params.VEGATOOLS_BRANCH, install: true ],
                            [ name: 'vegaprotocol/performance', branch: params.PERFORMANCE_BRANCH ],
                        ]
                        def reposSteps = repositories.collectEntries{value -> [
                            value.name,
                            {
                                def directory = value.name.split('/')[1]
                                gitClone([
                                    url: 'git@github.com:' + value.name + '.git',
                                    branch: value.branch,
                                    directory: directory,
                                    credentialsId: 'vega-ci-bot',
                                    timeout: 2,
                                ])
                                if (value.install) {
                                    dir(directory) {
                                        sh 'go install ./...'
                                    }
                                }
                            }
                        ]}
                        parallel reposSteps
                    }
                    sh '''
                        mv vega performance
                        mv vegatools performance
                        mv vegacapsule performance
                    '''
                }
            }
            stage('prerequisites') {
                steps {
                    dir('performance') {
                        sh 'bash prerequisites.sh'
                    }
                }
            }
            stage('performance') {
                steps {
                    dir('performance') {
                        sh 'bash runtests.sh'
                    }
                }
            }
        }
        post {
            always {
                archiveArtifacts(
                    artifacts: 'performance/results.sql'
                )
                cleanWs()
            }
        }
    }
}
