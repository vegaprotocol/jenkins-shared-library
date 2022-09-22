void call(Map additionalConfig=[:]) {
    Map defaultConfig = [
        // networkName: "",
        // application: "",
        // version: "",
        directory: 'k8s',
        timeout: 10,
        period: 60,
        forceRestart: false,
        k8sObjectName: '',
    ]

    Map config = defaultConfig + additionalConfig

    if (config.networkName == "") {
        error('[releaseKubernetesApp] config.networkName cannot be empty')
    }

    if (config.application == "") {
        error('[releaseKubernetesApp] config.application cannot be empty')
    }

    if (config.version == "") {
        error('[releaseKubernetesApp] config.version cannot be empty')
    }

    if (config.timeout < 1) {
        error('[releaseKubernetesApp] config.timeout must be positive')
    }

    if (config.k8sObjectName == "") {
        config.k8sObjectName = 'pod/' + config.application + '-app-0';
    }

    makeCommit(
        directory: config.directory,
        url: 'git@github.com:vegaprotocol/k8s.git',
        branchName: config.networkName + '-' + config.application + '-update',
        commitMessage: '[Automated] ' + config.application + ' version update for ' + config.networkName,
        commitAction: 'echo ' + config.version + ' > charts/apps/' + config.application + '/' + config.networkName + '/VERSION'
    )


    timeout(time: config.timeout, unit: 'MINUTES') {
         withGoogleSA('gcp-k8s') {
            if (config.forceRestart && config.k8sObjectName.contains('pod/')) {
                sleep 60 // Just wait for git commit to be picked up by argocd
                sh 'kubectl delete -n ' + config.networkName + ' ' + config.k8sObjectName
            }

            waitUntil {
                try {
                    imageVersion = sh (
                        script: '''
                        #!/bin/bash +x
                        kubectl get ''' + config.k8sObjectName + ''' -n ''' + config.networkName + ''' -o yaml \
                            | grep "''' + config.application + '''" \
                            | grep "image:" \
                            | grep ''' + config.version + ''' \
                            || echo "NOT READY"
                        ''',
                        returnStdout: true,
                    ).trim()

                    if (!imageVersion.contains(config.version)) {
                        throw "application not ready yet"
                    }
                } catch (err) {
                    print(err)
                    sleep config.period
                    return false
                }
                return true
            }
        }
    }
}

//Example usage:
// releaseKubernetesApp([
//     networkName: "stagnet3",
//     application: "vegawallet",
//     version: "a7ce1fa2",
//     forceRestart: true,
//     directory: 'k8s',
//     timeout: 10,
// ])