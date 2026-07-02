@Library('pipeline-library') _
skipRemainingStages = false

def deployEnvironments = ['', 'development', 'dev1', 'qa', 'qa1', 'staging', 'production'].join('\n')

pipeline {
    agent any
    parameters {
            choice(name: 'NAMESPACE',
               choices: deployEnvironments,
               description: 'Choose the Environment to deploy.'
            )
            booleanParam(name: 'cleanup', defaultValue: false, description: 'Maven repository cleanup')
            booleanParam(name: 'lock', defaultValue: false, description: 'Lock New Functionality Branch')
    }

    options {
        disableConcurrentBuilds()
    }

    post {
        always {
            cleanWs()
        }
    }

    stages {
        stage ('Prepare ENV Variables') {
            when {
                allOf {
                    expression { "${NAMESPACE}" != null }
                }
            }
            steps {
                script {
                    env.JAVA_HOME = "/usr/lib/jvm/jdk-20/"
                    shortCommit = sh(returnStdout: true, script: "git log -n 1 --pretty=format:'%h'").trim()
                    product = "itrade-load-creation-agent"
                    if (BRANCH_NAME.contains('release')) {
                        TAG = BRANCH_NAME.split('-')[1] + "_" + "${shortCommit}"
                    } else {
                        TAG = "${shortCommit}"
                    }
//                  Checking if we need to build an image. It could already exist.
                    env.IF_IMAGE_EXISTS = sh(returnStdout: true, script: "gcloud container images list-tags gcr.io/hwyhaul-backend/${product} --filter=${TAG} --format=json").trim()

                    if (env.IF_IMAGE_EXISTS == "[]"){
                        BUILD_NEED = true
                    } else {
                        BUILD_NEED = false
                        
                    }
                }
            }
        }

        stage('Cleanup'){
            when {
                expression { "${cleanup}" == "true" };
            }
            steps {
                script {
                    cleanup.cleanup
                }
            }
        }

        stage('Build itrade-load-creation-agent'){
            steps {
                script {
                    try {
                        javaBuild.build "${product}"
                        bitbucketStatusNotify(
                            buildState: 'SUCCESSFUL'
                        )
                    } catch(Exception e) {
                        bitbucketStatusNotify(
                            buildState: 'FAILED'
                        )
                        currentBuild.result = 'FAILURE'
                        error('Aborting the build.')
                        throw e
                        return
                    }
                }
            }
        }

        stage('Build docker image'){
            when {
                allOf {
                    expression { "${NAMESPACE}" != "" };
                    anyOf {
                        expression { BUILD_NEED == true};
                    }
                }
            }
            steps {
                script {
                    sh "ls -la"
                    sh "ls -la ./itrade-load-creation-agent-infrastructure/target/"
                    dockerBuild.image "${product}", "${TAG}"
                }
            }
        }

        stage ('Deploy'){
            when {
                anyOf {
                    expression { "${NAMESPACE}" != "" };
                }
            }
            steps {
                dir ('infra'){
                    script {
                        git credentialsId: '0dd102f4-43c8-4fa0-b601-81a064e504dc', url: 'git@bitbucket.org:hwyhaul/infra.git'
                    }
                }
                script {
                    sh "kubectl apply -f infra/helm/microservice/values/${product}/${NAMESPACE}-secret.yaml"
                    helmDeploy.helmDeploy "${NAMESPACE}", "${TAG}", "${product}"
                }
            }
        }
    }
}
