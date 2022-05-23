def call(String credentialsId, Closure body) {
    withCredentials([file(credentialsId: credentialsId, variable: 'GC_KEY')]) {
        sh """
            if ! command -v gcloud; then
                curl -o gcloud.tgz https://dl.google.com/dl/cloudsdk/channels/rapid/downloads/google-cloud-cli-386.0.0-linux-x86_64.tar.gz
                tar -xf gcloud.tgz
                export PATH="\$PATH:\$PWD/google-cloud-sdk/bin"
            fi
            gcloud auth activate-service-account --key-file=\${GC_KEY}
            gcloud container clusters get-credentials gke-01 --region europe-west1 --project vegaprotocol
        """
        body()
    }
}