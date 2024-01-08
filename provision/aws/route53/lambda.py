import boto3
import json


def handler(event, context):
    print(json.dumps(event, indent=4))

    msg = json.loads(event['Records'][0]['Sns']['Message'])
    healthCheckId = msg['Trigger']['Dimensions'][0]['value']

    r53Client = boto3.client("route53")
    response = r53Client.update_health_check(
        HealthCheckId=healthCheckId,
        ResourcePath="/lb-check-failed-over"
    )

    print(json.dumps(response, indent=4, default=str))
    statusCode = response['ResponseMetadata']['HTTPStatusCode']
    if statusCode != 200:
        raise Exception("Route 53 Unexpected status code %d" + statusCode)
