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
        'vegaprotocol/vegacapsule': 'develop',
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
    selectedServers = vegautils.proxmoxNodeSelector(
        providedNode: params.NODE,
        collateParam: 6
    )
    pipeline {
        agent none
        options {
            // timeout(time: 120, unit: 'MINUTES')
            disableConcurrentBuilds()
            timestamps()
            ansiColor('xterm')
        }
        stages {
            stage('trigger provisioner') {
                steps {
                    script {
                        // implement logic that waits for jobs to be completed and blocks agents from scheduling new jobs...
                        selectedServers.each{ serversBatch ->
                            parallel serversBatch.collectEntries { name -> [
                                (name): {
                                    node(name) {
                                        def labels = vegautils.nodeLabels(name)

                                        // We can keep job as UNSTABLE when the cleanup is not finished
                                        catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') {

                                            retry(count: 3) {
                                                timeout(time: 10) {
                                                    _goClean()
                                                    _cleanupDocker()
                                                    _systemPackagesUpgrade()
                                                }
                                            }
                                        }

                                        // When the docker cache is not refreshed, it may cause further issues
                                        // like timeouts for NOMAD, so we want to have this failures.
                                        catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                                            retry(count: 3) {
                                                timeout(time: 5) {
                                                    withDockerLogin('vegaprotocol-dockerhub', true) {
                                                        _cacheDockerImages(dockerImages())
                                                    }
                                                }
                                            }
                                        }

                                        // Not much issues when the golang cache is not refreshed.
                                        catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') {
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

                                        timeout(time: 30, unit: 'SECONDS') {
                                            try {
                                                sh 'sudo systemctl reboot'
                                            } catch (e) {}
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
