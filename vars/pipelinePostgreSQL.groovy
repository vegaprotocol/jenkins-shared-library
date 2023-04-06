
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
            sshCommand(server, '''
            s3cmd \
            --access_key="$(awk \'/^repo1-s3-key=/ {split($0, a, "="); print a[2]}\' /etc/pgbackrest.conf)" \
            --secret_key="$(awk \'/^repo1-s3-key-secret=/ {split($0, a, "="); print a[2]}\' /etc/pgbackrest.conf)" \
            --ssl \
            --no-encrypt \
            --dump-config \
            --host="$(awk \'/^repo1-s3-endpoint=/ {split($0, a, "="); print a[2]}\' /etc/pgbackrest.conf)" \
            --host-bucket="%(bucket)s.$(awk \'/^repo1-s3-endpoint=/ {split($0, a, "="); print a[2]}\' /etc/pgbackrest.conf)" \
            | sudo tee /root/.s3cfg \
            ''')

            sshCommand(server, '''sudo s3cmd rm s3://$(awk '/^repo1-s3-bucket=/ {split($0, a, "="); print a[2]}' /etc/pgbackrest.conf)/$(awk '/^repo1-path=/ {split($0, a, "="); print a[2]}' /etc/pgbackrest.conf)''')
            sshCommand(server, 'sudo -u postgres pgbackrest --stanza=' + stanza + ' stanza-delete')
            sshCommand(server, 'sudo -u postgres pgbackrest --stanza=' + stanza + ' stanza-create')
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
