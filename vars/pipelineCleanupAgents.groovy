def call() {
    if (!params.NODE) {
        SLAVES = Jenkins.instance.computers.findAll{ "${it.class}" == "class hudson.slaves.SlaveComputer" }.collect{ it.name }.collate(3)
    }
    else {
        SLAVES = params.NODE.replaceAll(" ", "").split(",").toList().collate(3)
    }
    pipeline {
        agent none
        options {
            timestamps()
            ansiColor('xterm')
        }
        post {
            always {
                cleanWs()
            }
        }
        stages {
            stage('trigger provisioner') {
                steps {
                    script {
                        // implement logic that waits for jobs to be completed and blocks agents from scheduling new jobs...
                        SLAVES.each{ slavesBatch ->
                            parallel slavesBatch.collectEntries { name -> [
                                (name): {
                                    node(name) {
                                        catchError(buildResult: 'UNSTABLE') {
                                            def labels = Jenkins.instance.computers.find{ "${it.name}" == name }.assignedLabels
                                            sh '''
                                                sudo rm -rf /jenkins/GOPATH/pkg/*
                                                sudo rm -rf /jenkins/GOCACHE/*
                                                sudo apt-get update
                                                sudo apt-get upgrade -y
                                                sudo apt-get clean
                                                sudo find /jenkins/workspace -maxdepth 2 -type d -mtime +2 -exec rm -rf {} \\;
                                                docker system prune --all --force

                                            '''
                                            // rebuild cache only for machines that do actual builds
                                            if (!labels.contains('tiny')) {
                                                def repositories = [
                                                    [ name: 'vegaprotocol/vega', branch: 'develop' ],
                                                    [ name: 'vegaprotocol/vegacapsule', branch: 'main' ],
                                                    [ name: 'vegaprotocol/vegatools', branch: 'develop' ],
                                                    [ name: 'vegaprotocol/devopsscripts', 'main' ],
                                                    [ name: 'vegaprotocol/devopstools', 'main' ],
                                                ]
                                                def reposSteps = [failFast: true] + repositories.collectEntries{value -> [
                                                    value.name,
                                                    {
                                                    gitClone([
                                                        url: 'git@github.com:' + value.name + '.git',
                                                        branch: value.branch,
                                                        directory: value.directory ?: value.name.split('/')[1],
                                                        credentialsId: 'vega-ci-bot',
                                                        timeout: 3,
                                                    ])
                                                    }
                                                ]}
                                                parallel reposSteps
                                                def buildSteps = [failFase: true] + repositories.collectEntries{ value -> [
                                                    value.name,
                                                    {
                                                        retry(2) {
                                                            timeout(time: 25, unit: 'MINUTES') {
                                                                vegauitls.buildGoBinary(value.name.replaceAll('vegaprotocol/', ''), pwd() + value.name.replaceAll('vegaprotocol', '') + './')
                                                            }
                                                        }
                                                    }
                                                ]}
                                            }
                                        }
                                    }
                                }
                            ]}
                        }
                    }
                }
            }
        }
    }
}
