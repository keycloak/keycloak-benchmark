import boto3
import jmespath
import json


def cluster_writer_endpoint(cluster, region):
    rdsClient = boto3.client("rds", region)
    response = rdsClient.describe_db_cluster_endpoints(
        DBClusterIdentifier=cluster,
        Filters=[
            {
                'Name': 'db-cluster-endpoint-type',
                'Values': [
                    'WRITER',
                ]
            },
        ]
    )

    endpoints = response['DBClusterEndpoints'][0]
    if not endpoints:
        raise Exception("Unable to discover '%s' WRITER endpoint" % cluster)

    return endpoints['Endpoint']


def global_cluster_name(regionalClusterArn):
    rdsClient = boto3.client("rds")
    response = rdsClient.describe_global_clusters()
    query = "GlobalClusters[?GlobalClusterMembers[?DBClusterArn=='%s']].GlobalClusterIdentifier" % regionalClusterArn
    globalCluster = jmespath.search(query, response)
    if not globalCluster:
        raise Exception("Unable to discover GlobalCluster name for regional cluster '%s'" % regionalClusterArn)

    return globalCluster[0]


def hosted_zone_id(domain):
    r53Client = boto3.client("route53")

    response = r53Client.list_hosted_zones()
    hostedZoneId = jmespath.search("HostedZones[?starts_with(Name, '%s.')].Id" % domain, response)
    if not hostedZoneId:
        raise Exception("Unable to discover Hosted Zone Id")

    return hostedZoneId[0]


def handler(event, context):
    print(json.dumps(event, indent=4))

    clusterArn = event['resources'][0]
    arnArray = clusterArn.split(':')
    region = arnArray[3]
    cluster = arnArray[6]

    globalCluster = global_cluster_name(clusterArn)
    writerEndpoint = cluster_writer_endpoint(cluster, region)
    hostedZone = hosted_zone_id("keycloak-benchmark.com")

    r53Client = boto3.client("route53")
    response = r53Client.change_resource_record_sets(
        HostedZoneId=hostedZone,
        ChangeBatch={
            "Comment": "Switching endpoint on failover",
            "Changes": [
                {
                    "Action": "UPSERT",
                    "ResourceRecordSet": {
                        "Name": '%s.aurora-global.keycloak-benchmark.com' % globalCluster,
                        "Type": "CNAME",
                        "TTL": 60,
                        "ResourceRecords": [{"Value": writerEndpoint }]
                    }
                }
            ]
        }
    )

    print(json.dumps(response, indent=4, default=str))
    statusCode = response['ResponseMetadata']['HTTPStatusCode']
    if statusCode != 200:
        raise Exception("Route 53 Unexpected status code %d" + statusCode)
