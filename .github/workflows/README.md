# Running workflows for keycloak-benchmark repository on AWS

## Create secrets and variables

1. Go to repository Settings -> Secrets and variables
2. Create new secret `AWS_ACCESS_KEY_ID` with value of your AWS access key id.
3. Create new secret `AWS_SECRET_ACCESS_KEY` with value of your AWS access key value.
4. Create new secret `ROSA_TOKEN` with value of your ROSA token. See [Red Hat OpenShift Cluster Manager API](https://cloud.redhat.com/openshift/token) for more information.
5. Create new variable `AWS_DEFAULT_REGION` with value of your preferred AWS region, for example, `eu-central-1`.

## Create cluster

1. Go to repository Actions -> On the left side choose `ROSA Cluster - Create`
2. Click on Run workflow button
3. Fill in the form and click on Run workflow button
   1. Name of the cluster - the name of the cluster that will be later used for other workflows. Default value is `gh-${{ github.repository_owner }}`, this results in `gh-<owner of fork>`.
   2. Instance type for compute nodes - see [AWS EC2 instance types](https://aws.amazon.com/ec2/instance-types/). Default value is `m7g.2xlarge`.
   3. Deploy to multiple availability zones in the region - if checked, the cluster will be deployed to multiple availability zones in the region. Default value is `false`.
   4. Number of worker nodes to provision - number of compute nodes in the cluster. Default value is `2`.
4. Wait for the workflow to finish.

## Destroy cluster

1. Go to repository Actions -> On the left side choose `ROSA Cluster - Destroy`
2. Click on Run workflow button
3. Fill in the form and click on Run workflow button
   1. Name of the cluster - the name of the cluster to destroy. Default value is `gh-${{ github.repository_owner }}`, this results in `gh-<owner of fork>`.
4. Wait for the workflow to finish.

## Periodic Jobs
1. We are managing the creation of shared ROSA clusters' creation and deletion using below workflows.
   1. `.github/workflows/rosa-cluster-auto-delete-on-schedule.yml`
   2. `.github/workflows/rosa-cluster-auto-provision-on-schedule.yml`
2. Optionally you can run the `task keepalive` from provision/openshift directory against your OpenShift cluster to keep it alive, from the `rosa-cluster-auto-delete-on-schedule` workflow's delete activity on the defined schedule time.


## Run benchmark

1. Go to repository Actions -> On the left side choose `ROSA Cluster - Run Benchmark`
2. Click on Run workflow button
3. Fill in the form and click on Run workflow button
   1. Name of the cluster - the name of the cluster that will be used for running benchmark. Default value is `gh-${{ github.repository_owner }}`, this results in `gh-<owner of fork>`.
4. The workflow will perform the following steps
   1. Connect to the cluster
   2. Provision new Keycloak instance including monitoring stack.
   3. Create dataset with 1 realm, 1 client and 100 users.
   4. Run Keycloak benchmark with `keycloak.scenario.authentication.AuthorizationCode` scenario.
5. Wait for the workflow to finish. The Gatling results will be available in the `artifacts` section of the workflow run.
