void SshCommand(String serverHost, String command) {
    withCredentials([sshUserPrivateKey(
        credentialsId: 'ssh-vega-network',
        keyFileVariable: 'PSSH_KEYFILE',
        usernameVariable: 'PSSH_USER'
    )]) {
        sh '''ssh \
            -o "StrictHostKeyChecking=no" \
            -i "''' + PSSH_KEYFILE + '''" \
            ''' + PSSH_USER + '''@''' + serverHost + ''' \
            ''' + command
    }
}

String S3Command(String s3Endpoint, String subcommand) {
    withCredentials([usernamePassword(
        credentialsId: 'digitalocean-s3-credentials', 
        passwordVariable: 'SECRET_KEY', 
        usernameVariable:'ACCESS_KEY'
    )]) {
        return '''s3cmd \
            --access_key="''' + ACCESS_KEY + '''" \
            --secret_key="''' + SECRET_KEY + '''" \
            --host="''' + s3Endpoint + '''" \
        ''' + subcommand
    }
}

void call() {
    String s3BucketName = params.S3_BUCKET_NAME ?: 'vega-chain-data-backups'
    String networkName = params.NET_NAME ?: 'stagnet3'
    String nodeName = params.NODE_NAME ?: 'n08'
    Map networkStatistics
    String chainId
    String serverHost = nodeName + '.' + networkName + '.vega.xyz'
    String backupDestination 

    pipeline {
        agent any
        options {
            skipDefaultCheckout()
            timeout(time: 120, unit: 'MINUTES')
            timestamps()
        }
        stages {
            stage('Collect network info') {
                steps {
                    script {
                        networkStatistics = vegautils.networkStatistics(networkName)
                        chainId = networkStatistics.statistics.chainId
                        backupDestination = 's3://' + s3BucketName + '/' + networkName + '/' + chainId
                    }
                }
            }

            stage('Stop vega network') {
                steps {
                    script {
                        SshCommand(serverHost, "sudo systemctl stop vegavisor")
                        SshCommand(serverHost, "sudo systemctl status vegavisor || echo ''")
                        sleep 60
                    }
                }
            }

            stage('Backup core state') {
                steps {
                    script {
                        String cmd = S3Command('fra1.digitaloceanspaces.com', 'sync /home/vega/vega_home/ ' + backupDestination + '/vega_home/')
                        SshCommand(serverHost, "sudo " + cmd)
                    }
                }
            }

            stage('Backup tendermint state') {
                steps {
                    script {
                        String cmd = S3Command('fra1.digitaloceanspaces.com', 'sync /home/vega/tendermint_home/ ' + backupDestination + '/tendermint_home/')
                        SshCommand(serverHost, "sudo " + cmd)
                    }
                }
            }
        }

        post {
            always {
                cleanWs()
                script {
                    SshCommand(serverHost, "sudo systemctl start vegavisor")
                    SshCommand(serverHost, "sudo systemctl status vegavisor")
                }
            }
        }
    }
}