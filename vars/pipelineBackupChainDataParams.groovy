String S3Command(String subcommand) {
        return '''s3cmd ''' + subcommand
}

String S3cmdInitCommand(String s3Host, String s3BucketHost) {
    withCredentials([usernamePassword(
        credentialsId: 'digitalocean-s3-credentials', 
        passwordVariable: 'SECRET_KEY', 
        usernameVariable:'ACCESS_KEY'
    )]) {
         return '''s3cmd \
            --access_key="''' + ACCESS_KEY + '''" \
            --secret_key="''' + SECRET_KEY + '''" \
            --ssl \
            --no-encrypt \
            --dump-config \
            --host="''' + s3Host + '''" \
            --host-bucket="''' + s3BucketHost + '''" | sudo tee /root/.s3cfg \
        '''
    }
} 

void call() {
    String s3BucketName = params.S3_BUCKET_NAME ?: 'vega-chain-backup-sfo3'
    String networkName = env.NET_NAME ?: 'stagnet1'
    String nodeName = params.NODE_NAME ?: 'be02'
    String stanzaName = params.STANZA_NAME ?: 'main_archive'

    boolean fullPostgresqlBackup = (params.POSTGRESQL_FULL_BACKUP ?: false).toBoolean()

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
            
        disableConcurrentBuilds()
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

            stage('Configure s3cmd') {
                steps {
                    script {
                        vegautils.sshCommand(serverHost, "sudo " + S3cmdInitCommand("sfo3.digitaloceanspaces.com", "%(bucket)s.sfo3.digitaloceanspaces.com"))
                    }
                }
            }

            stage('Stop vega network') {
                steps {
                    script {
                        vegautils.sshCommand(serverHost, "sudo systemctl stop vegavisor")
                        vegautils.sshCommand(serverHost, "sudo systemctl status vegavisor || echo ''")
                        sleep 30
                    }
                }
            }

            stage('Backup postgresql') {
                steps {
                    script {
                        String backupType = 'incr'
                        vegautils.sshCommand(serverHost, 'sudo -u postgres pgbackrest --stanza=' + stanzaName + ' --log-level-console=info --type=' + backupType + ' backup')
                    }
                }
            }

            stage('Backup core state') {
                steps {
                    script {
                        String cmd = S3Command('sync /home/vega/vega_home/ ' + backupDestination + '/vega_home/')
                        vegautils.sshCommand(serverHost, "sudo " + cmd)
                    }
                }
            }

            stage('Backup tendermint state') {
                steps {
                    script {
                        String cmd = S3Command('sync /home/vega/tendermint_home/ ' + backupDestination + '/tendermint_home/')
                        vegautils.sshCommand(serverHost, "sudo " + cmd)
                    }
                }
            }
        }

        post {
            always {
                cleanWs()
                script {
                    vegautils.sshCommand(serverHost, "sudo systemctl start vegavisor")
                    vegautils.sshCommand(serverHost, "sudo systemctl status vegavisor")
                }
            }
        }
    }
}

// Example call:
// library (
//     identifier: "vega-shared-library@main",
//     changelog: false,
// )
// call()