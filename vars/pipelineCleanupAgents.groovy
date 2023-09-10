
List<String> dockerImages(){
    return [
        "vegaprotocol/vegacapsule-timescaledb:2.8.0-pg14-v0.0.1",
        "vegaprotocol/grpc-plugins:latest",
        "vegaprotocol/clef:v2.2.1",
        "vegaprotocol/ganache:v1.2.4",
        "golang:1.20-alpine3.18",
        "alpine:3.18",
    ]
}

Map<String, String> gitRepositories() {
    return [
        'vegaprotocol/vega': 'develop',
        'vegaprotocol/vegacapsule': 'main',
        'vegaprotocol/vegatools': 'develop',
        'vegaprotocol/devopsscripts': 'main',
        'vegaprotocol/devopstools': 'main',
    ]
}

void _goClean() {
    sh label: 'Clean Golang cache', script: '''
        sudo rm -rf /jenkins/GOPATH/pkg/*
        sudo rm -rf /jenkins/GOCACHE/*
    '''
}

void _systemPackagesUpgrade() {
    sh label: 'Upgrade system packages', script: '''
        sudo apt-get update
        sudo apt-get upgrade -y
        sudo apt-get clean
    '''
}

void _cleanWorkspaces() {
    sh label: 'Clean workspaces', script: '''
        sudo find /jenkins/workspace -maxdepth 2 -type d -mtime +2 -exec rm -rf {} \\;
    '''
}

void _cleanupDocker() {
    int runningContainers = vegautils.shellOutput('docker ps -q | wc -l') as int
    int localImages = vegautils.shellOutput('docker images -a -q | wc -l') as int

    if (runningContainers > 0) {
        sh label: 'Kill all running docker containers', script: 'docker kill $(docker ps -q)'
    }
    if (localImages > 0) {
        sh label: 'Prune all docker artifacts', script: 'docker system prune --all --volumes --force'
        sh label: 'Remove all docker images', script: 'docker rmi --force $(docker images -a -q) || echo "All images removed by prune"'
        sh label: 'Prune all docker artifacts', script: 'docker system prune --all --volumes --force'
    }
}

void _cacheDockerImages(List<String> images) {
    images.each { image ->
        sh label: 'Pull docker image: ' + image, script: 'docker pull ' + image
    }
}

void _cacheGoBuild(Map<String, String> repositories) {
    repositories.each { repository, branch ->
        String directory = repository.split('/')[-1]

        gitClone([
            url: 'git@github.com:' + repository + '.git',
            branch: branch,
            directory: directory,
            credentialsId: 'vega-ci-bot',
            timeout: 5,
        ])

        dir (directory) {
            sh label: 'Build ' + directory, script: 'go build ./...'
        }
    }
}

void call() {
    String nodeSelector = params.NODE ?: ''

    if (nodeSelector.length() < 1) {
        SLAVES = Jenkins
            .instance
            .computers
            .findAll{ "${it.class}" == "class hudson.slaves.SlaveComputer" && it.isOnline() }
            .collect{ it.name }
            .collate(3)
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
                                            def labels = Jenkins
                                                .instance
                                                .computers
                                                .find{ "${it.name}" == name }
                                                .getAssignedLabels()
                                                .collect {it.toString()}
                                            print('Labels for ' + name + ': ' + labels.join(', '))
                                            
                                            retry(count: 3) {
                                                timeout(time: 10) {
                                                    _goClean()
                                                    _cleanupDocker()
                                                    _systemPackagesUpgrade()
                                                }
                                            }


                                            retry(count: 3) {
                                                timeout(time: 5) {
                                                    _cacheDockerImages(dockerImages())
                                                }
                                            }

                                            // rebuild cache only for machines that do actual builds
                                            if (!labels.contains('tiny')) {
                                                retry(count: 3) {
                                                    timeout(time: 5) {
                                                        _cacheGoBuild(gitRepositories())
                                                    }
                                                }
                                            }
                                        }

                                        retry(count: 3) {
                                            timeout(time: 5) {
                                                _cleanWorkspaces()
                                            }
                                        }
                                        cleanWs()
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
