
void sshCommand(String serverHost, String command) {
    withCredentials([sshUserPrivateKey(
        credentialsId: 'ssh-vega-network',
        keyFileVariable: 'PSSH_KEYFILE',
        usernameVariable: 'PSSH_USER'
    )]) {
        sh '''
            set -x;
            ssh \
            -o "StrictHostKeyChecking=no" \
            -i "''' + PSSH_KEYFILE + '''" \
            ''' + PSSH_USER + '''@''' + serverHost + ''' \
            \'''' + command + '''\''''
    }
}


void scpCommand(String serverHost, String remoteFile, String localFile, List<String> additionalFlags = []) {
    withCredentials([sshUserPrivateKey(
        credentialsId: 'ssh-vega-network',
        keyFileVariable: 'PSSH_KEYFILE',
        usernameVariable: 'PSSH_USER'
    )]) {
      String flags = additionalFlags.join(' ')

        sh '''
            set -x;
            scp \
            -o "StrictHostKeyChecking=no" \
            -i "''' + PSSH_KEYFILE + '''" ''' + flags + ''' \
            ''' + PSSH_USER + '''@''' + serverHost + ''':\'''' + remoteFile + '''\' \
            \'''' + localFile + '''\' 
            '''
    }
}

/* groovylint-disable
  BuilderMethodWithSideEffects, CompileStatic, DuplicateStringLiteral,
  FactoryMethodName, VariableTypeRequired */
void call() {
  String server = params.SERVER ?: 'be02.stagnet1.vega.xyz'
  String operation = params.OPERATION ?: 'remove' // or restore or remove
  String backupType = params.BACKUP_TYPE ?: 'full' // or incr
  String stanza = params.STANZA ?: 'main_archive'
  String timeoutString = params.TIMEOUT ?: '360'
  boolean stopNetwork = params.STOP_NETWORK ?: true
  String postgresqlData = params.POSTGRESQL_DATA ?: '/home/vega/postgresql'

  pipeline {
    agent any

    options {
      ansiColor('xterm')
      timestamps()
      timeout(time: timeoutString, unit: 'MINUTES')
      disableConcurrentBuilds()
    }


    stages {
      stage('prepare') {
        steps {
          cleanWs()
          script {
            int buildNumber = currentBuild.number
            switch(operation) {
              case 'backup':
                currentBuild.displayName = sprintf("#%d: Backup %s, %s", buildNumber, server, backupType)
                break
              case 'restore':
                currentBuild.displayName = sprintf("#%d: Restore %s", buildNumber, server)
                break
              case 'remove':
                currentBuild.displayName = sprintf("#%d: Remove remote backup for %s", buildNumber, server)
                break
            }
          }
        }
      }

      stage('StopNetwork') {
        when {
          expression {
            stopNetwork
          }
        }
        steps {
          script {
              sshCommand(server, 'sudo systemctl stop vegavisor || echo "Stop not required"')
          }
        }
      }

      stage('Check requirements') {
        steps {
          script {
            try {
              sshCommand(server, 'sudo -u postgres pgbackrest version')
            } catch (err) {
              error('Missing pgbackrest command')
            }

            try {
              sshCommand(server, 'ls /etc/pgbackrest.conf')
            } catch (err) {
              error('Missing configuration for pgbackrest')
            }
          }
        }
      }

      stage('Backup') {
        when {
          expression { 
            operation == 'backup'
          }
        }

        steps {
          script {
            try {
              sshCommand(server, 'sudo -u postgres pgbackrest --stanza=' + stanza + ' check')
            } catch (err) {
              error('Invalid configuration for pgbackrest')
            }

            sshCommand(server, 'sudo -u postgres pgbackrest --stanza=' + stanza + ' --type=' + backupType + ' backup')
          }
        }
      }

      stage('Remove remote backup') {
        when {
          expression { 
            operation == 'remove'
          }
        }

        steps {
          script {
            scpCommand(server, '/etc/pgbackrest.conf', './pgbackrest.conf')
            Map pgBackrestConfig = readProperties file: "pgbackrest.conf"

            print(pgBackrestConfig)
            print(pgBackrestConfig["repo1-s3-endpoint"])

            sshCommand(server, '''
            s3cmd \
            --access_key="''' + pgBackrestConfig["repo1-s3-key"] + '''" \
            --secret_key="''' + pgBackrestConfig["repo1-s3-key-secret"] + '''" \
            --ssl \
            --no-encrypt \
            --dump-config \
            --host="''' + pgBackrestConfig["repo1-s3-endpoint"] + '''" \
            --host-bucket="%(bucket)s.''' + pgBackrestConfig["repo1-s3-endpoint"] + '''" \
            | sudo tee /root/.s3cfg \
            ''')

            sshCommand(server, '''sudo s3cmd rm --recursive s3://''' + pgBackrestConfig["repo1-s3-bucket"] + '''/''' + pgBackrestConfig["repo1-path"])
            sshCommand(server, 'sudo systemctl stop postgresql')
            sleep 30 // give it some time
            sshCommand(server, 'sudo -u postgres pgbackrest --stanza=' + stanza + ' stop')
            sshCommand(server, 'sudo -u postgres pgbackrest --stanza=' + stanza + ' stanza-delete')
            sshCommand(server, 'sudo systemctl start postgresql')
            sshCommand(server, 'sudo -u postgres pgbackrest --stanza=' + stanza + ' stanza-create')
            sshCommand(server, 'sudo -u postgres pgbackrest --stanza=' + stanza + ' check')
          }
        }
      }

      stage('Restore') {
        when {
          expression { 
            operation == 'restore'
          }
        }

        steps {
          script {
            try {
              sshCommand(server, 'sudo -u postgres pgbackrest --stanza=' + stanza + ' check')
            } catch (err) {
              error('Invalid configuration for pgbackrest')
            }

            sshCommand(server, 'sudo systemctl stop postgresql')
            sshCommand(server, 'sudo rm -rf ' + postgresqlData)
            sshCommand(server, 'sudo mkdir -p ' + postgresqlData)
            sshCommand(server, 'sudo chown postgres:postgres ' + postgresqlData)
            sshCommand(server, 'sudo -u postgres pgbackrest --stanza=' + stanza + ' --delta restore')
          }
        }
      }
    }
  }
}

call()
