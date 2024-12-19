import pandas as pd
import requests
import argparse
from pandas import json_normalize
import logging
import json

def setup_logger(log_file):
    # Set up logging to a file
    logging.basicConfig(filename=log_file, level=logging.DEBUG,
                        format='%(asctime)s %(levelname)s %(message)s')
    logger = logging.getLogger()
    return logger

def fetch_and_process_json(github_user, github_repo, branch_name, json_directory, logger):
    # GitHub API URL to list files in the directory on a specific branch
    api_url = f'https://api.github.com/repos/{github_user}/{github_repo}/contents/{json_directory}?ref={branch_name}'

    # Fetch the list of files in the directory
    response = requests.get(api_url)
    files = response.json()

    # Dictionary to store DataFrames for each test
    data_frames = {
        'memoryUsageTest': [],
        'cpuUsageForLoginsTest': [],
        'cpuUsageForCredentialGrantsTest': []
    }

    basic_df = []

    # Fetch each JSON file and append to the corresponding list
    for file in files:
        if file['name'].endswith('.json'):
            file_url = file['download_url']
            file_response = requests.get(file_url)
            file_json = file_response.json()
            df = pd.json_normalize(file_json)
            basic_df.append(df)

            # Debug: log the JSON content
            logger.debug("Processing file: %s", file['name'])
            logger.debug("JSON content: %s", json.dumps(file_json, indent=2))

            # Normalize the JSON to extract specific fields for each test
            for test in data_frames.keys():
                if test in file_json:
                    df = json_normalize(
                        file_json,
                        record_path=[test, 'statistics'],
                        meta=[
                            'start',
                            'context',
                            [test, 'activeSessionsPer500MbPerPod'],
                            [test, 'userLoginsPerSecPer1vCpuPerPod'],
                            [test, 'credentialGrantsPerSecPer1vCpu']
                        ],
                        record_prefix=f'{test}.',
                        errors='ignore'
                    )
                    data_frames[test].append(df)

    combined_df = pd.concat(basic_df, ignore_index=True)
    perf_across_deployments_df = combined_df[['start', 'context.externalInfinispanFeatureEnabled', 'cpuUsageForLoginsTest.userLoginsPerSecPer1vCpuPerPod', 'cpuUsageForCredentialGrantsTest.credentialGrantsPerSecPer1vCpu', 'memoryUsageTest.activeSessionsPer500MbPerPod']]

    print(perf_across_deployments_df.to_csv(index=False))
    # Concatenate all DataFrames for each test into a single DataFrame
    combined_data_frames = {test: pd.concat(data_frames[test], ignore_index=True) for test in data_frames}

    # Log the columns of the combined DataFrames
    for test, df in combined_data_frames.items():
        logger.debug(f"{test} DataFrame columns: {df.columns.tolist()}")
        logger.debug(f"{test} DataFrame sample: {df.head()}")

    return combined_data_frames

def save_to_csv(data_frames, json_directory, output_directory):
    # Columns to include in the final CSVs for each test
    columns_to_include = {
        'memoryUsageTest': [
            'start',
            'context',
            'memoryUsageTest.name',
            'memoryUsageTest.activeSessionsPer500MbPerPod',
            'memoryUsageTest.meanResponseTime.total',
            'memoryUsageTest.percentiles1.total',
            'memoryUsageTest.meanNumberOfRequestsPerSecond.total'
        ],
        'cpuUsageForLoginsTest': [
            'start',
            'context',
            'cpuUsageForLoginsTest.name',
            'cpuUsageForLoginsTest.userLoginsPerSecPer1vCpuPerPod',
            'cpuUsageForLoginsTest.meanResponseTime.total',
            'cpuUsageForLoginsTest.percentiles1.total',
            'cpuUsageForLoginsTest.meanNumberOfRequestsPerSecond.total'
        ],
        'cpuUsageForCredentialGrantsTest': [
            'start',
            'context',
            'cpuUsageForCredentialGrantsTest.name',
            'cpuUsageForCredentialGrantsTest.credentialGrantsPerSecPer1vCpu',
            'cpuUsageForCredentialGrantsTest.meanResponseTime.total',
            'cpuUsageForCredentialGrantsTest.percentiles1.total',
            'cpuUsageForCredentialGrantsTest.meanNumberOfRequestsPerSecond.total'
        ]
    }

    for test, df in data_frames.items():
        # Reorder columns to include only the desired ones
        available_columns = [col for col in columns_to_include[test] if col in df.columns]
        df = df[available_columns]

        test_date = json_directory.replace("/", "_")
        # Save to CSV
        csv_file_path = f"{output_directory}/{test_date}_{test}_results.csv"
        df.to_csv(csv_file_path, index=False)
        print(f"Saved {test} results to {csv_file_path}")

def main():
    parser = argparse.ArgumentParser(description="Process JSON files from a GitHub repository.")
    parser.add_argument('json_directory', type=str, help='The directory in the GitHub repository containing JSON files.')
    parser.add_argument('output_directory', type=str, help='The directory to save the CSV files.')
    args = parser.parse_args()

    github_user = 'keycloak'
    github_repo = 'keycloak-benchmark'
    branch_name = 'result-data'
    json_directory = args.json_directory
    output_directory = args.output_directory

    # Set up logger
    log_file = 'perf_insights.log'
    logger = setup_logger(log_file)

    data_frames = fetch_and_process_json(github_user, github_repo, branch_name, json_directory, logger)
    save_to_csv(data_frames, json_directory, output_directory)

if __name__ == '__main__':
    main()
