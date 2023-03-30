
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
  String operation = params.OPERATION ?: 'backup' // or restore
  String backupType = params.BACKUP_TYPE ?: 'incr'
  String stanza = params.STANZA ?: 'main_archive'
  String timeoutString = params.TIMEOUT ?: '1'

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
            }
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

            try {
              sshCommand(server, 'sudo -u postgres pgbackrest --stanza=' + stanza + ' check')
            } catch (err) {
              error('Invalid configuration for pgbackrest')
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
            sshCommand(server, 'sudo -u postgres pgbackrest --stanza=' + stanza + ' --type=' + backupType + ' backup')
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
            sshCommand(server, 'sudo systemctl stop postgresql')
            sshCommand(server, '''sudo -u postgres rm -rf $(awk '/^pg1-path=/ { split($0, a, "="); print a[2]; }' /etc/pgbackrest.conf)''')
            sshCommand(server, 'sudo -u postgres pgbackrest --stanza=' + stanza + ' --delta restore ')
          }
        }
      }
    }
  }
}

call()
