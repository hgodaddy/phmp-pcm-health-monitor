pipeline {
    agent any

    parameters {
        choice(name: 'PHMP_MODE', choices: ['simulate', 'device'], description: 'Execution mode')
        choice(name: 'PHMP_REGION', choices: ['both', 'US', 'EU'], description: 'Region scope')
        string(name: 'US_SERIAL', defaultValue: '', description: 'Optional US device serial override')
        string(name: 'EU_SERIAL', defaultValue: '', description: 'Optional EU device serial override')
        string(name: 'CLOUD_TOKEN', defaultValue: '', description: 'Optional cloud API bearer token')
    }

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '30'))
    }

    environment {
        JAVA_HOME = tool name: 'JDK21', type: 'jdk'
        PATH = "${env.JAVA_HOME}/bin:${env.PATH}"
        PHMP_US_SERIAL = "${params.US_SERIAL}"
        PHMP_EU_SERIAL = "${params.EU_SERIAL}"
        PHMP_CLOUD_TOKEN = "${params.CLOUD_TOKEN}"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build') {
            steps {
                sh 'mvn -B -DskipTests clean package'
            }
        }

        stage('Validate US') {
            when {
                expression { return params.PHMP_REGION == 'both' || params.PHMP_REGION == 'US' }
            }
            steps {
                sh """
                  mvn -B test \
                    -P${params.PHMP_MODE},us \
                    -Dphmp.mode=${params.PHMP_MODE} \
                    -Dphmp.region=US \
                    -Dgroups=us
                """
            }
        }

        stage('Validate EU') {
            when {
                expression { return params.PHMP_REGION == 'both' || params.PHMP_REGION == 'EU' }
            }
            steps {
                sh """
                  mvn -B test \
                    -P${params.PHMP_MODE},eu \
                    -Dphmp.mode=${params.PHMP_MODE} \
                    -Dphmp.region=EU \
                    -Dgroups=eu
                """
            }
        }

        stage('Allure Report') {
            steps {
                sh './scripts/allure-report.sh || true'
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: 'reports/**/*.html,logs/**/*.txt,target/surefire-reports/**,target/allure-results/**,target/site/**',
                               allowEmptyArchive: true
            junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml'
        }
        success {
            echo 'PHMP nightly gate: PASS'
        }
        failure {
            echo 'PHMP nightly gate: FAIL'
        }
    }
}
