/* groovylint-disable Indentation */
void call(Map additionalConfig) {
  Map defaultCconfig = [
      command: '',
      network: '',
      directory: 'devops-infra',
      sshCredentialsId: 'ubuntu-ansible-key',
      dockerCredentialsId: 'github-vega-ci-bot-artifacts',
      gcpCredentials: 'gcp-k8s',
      timeout: 15,
      label: null,
  ]

  Map config = defaultCconfig + additionalConfig
  timeout(config.timeout) {
    dir(config.directory) {
      withCredentials([sshUserPrivateKey(
          credentialsId: config.sshCredentialsId,
          keyFileVariable: 'PSSH_KEYFILE',
          usernameVariable: 'PSSH_USER'
      )]) {
        withDockerRegistry([
            credentialsId: config.dockerCredentialsId,
            url: 'https://ghcr.io'
        ]) {
          withGoogleSA(config.gcpCredentials) {
            sh label: config.label,
              script: './veganet.sh ' + config.network + ' ' + config.command
          }
        }
      }
    }
  }
}