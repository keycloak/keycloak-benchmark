import pandas as pd
import matplotlib.pyplot as plt
import glob
import os
import argparse

def main():
    parser = argparse.ArgumentParser(description="Generate time series graphs from CSV files.")
    parser.add_argument('output_directory', type=str, help='The directory containing the CSV files.')
    args = parser.parse_args()

    output_directory = args.output_directory

    # Find all CSV files matching '*_results.csv' in the output directory
    csv_files = glob.glob(os.path.join(output_directory, '*_results.csv'))

    if not csv_files:
        print(f"No CSV files found in directory {output_directory}")
        return

    # Dictionary to store DataFrames for each test type
    test_data = {
        'memoryUsageTest': [],
        'cpuUsageForLoginsTest': [],
        'cpuUsageForCredentialGrantsTest': []
    }

    # Process each CSV file and collect data per test type
    for csv_file in csv_files:
        df = pd.read_csv(csv_file)

        # Ensure 'start' column exists and parse it as datetime
        if 'start' in df.columns:
            df['start'] = pd.to_datetime(df['start'])
        else:
            print(f"'start' column not found in {csv_file}. Skipping.")
            continue

        # Determine the test type based on the filename
        filename = os.path.basename(csv_file)
        if 'memoryUsageTest' in filename:
            test_name = 'memoryUsageTest'
            metric_column = 'memoryUsageTest.activeSessionsPer500MbPerPod'
        elif 'cpuUsageForLoginsTest' in filename:
            test_name = 'cpuUsageForLoginsTest'
            metric_column = 'cpuUsageForLoginsTest.userLoginsPerSecPer1vCpuPerPod'
        elif 'cpuUsageForCredentialGrantsTest' in filename:
            test_name = 'cpuUsageForCredentialGrantsTest'
            metric_column = 'cpuUsageForCredentialGrantsTest.credentialGrantsPerSecPer1vCpu'
        else:
            print(f"Unknown test in filename {filename}. Skipping.")
            continue

        if metric_column not in df.columns:
            print(f"Column '{metric_column}' not found in {csv_file}. Skipping.")
            continue

        # Keep only necessary columns
        df = df[['start', metric_column]]

        # Append to the list for the test type
        test_data[test_name].append(df)

    # For each test type, concatenate data and plot
    for test_name, data_frames in test_data.items():
        if not data_frames:
            print(f"No data found for test {test_name}.")
            continue

        # Concatenate all data frames
        combined_df = pd.concat(data_frames)

        # Sort by 'start' time
        combined_df = combined_df.sort_values('start')

        metric_column = combined_df.columns[1]  # second column is the metric

        # Plot the metric over time
        plt.figure(figsize=(10, 6))
        plt.plot(combined_df['start'], combined_df[metric_column], marker='o', linestyle='-')
        if test_name == 'memoryUsageTest':
            ylabel = 'Active Sessions Per 500Mb Per Pod'
            title = 'Active Sessions Per 500Mb Per Pod over Time'
        elif test_name == 'cpuUsageForLoginsTest':
            ylabel = 'User Logins Per Second Per 1vCPU Per Pod'
            title = 'User Logins Per Second Per 1vCPU Per Pod over Time'
        elif test_name == 'cpuUsageForCredentialGrantsTest':
            ylabel = 'Credential Grants Per Second Per 1vCPU'
            title = 'Credential Grants Per Second Per 1vCPU over Time'
        else:
            ylabel = metric_column
            title = f'{metric_column} over Time'

        plt.title(title)
        plt.xlabel('Time')
        plt.ylabel(ylabel)
        plt.grid(True)
        plt.xticks(rotation=45)

        # Save the plot
        plot_filename = os.path.join(output_directory, f'{test_name}_combined_timeseries_plot.png')
        plt.tight_layout()
        plt.savefig(plot_filename)
        plt.close()
        print(f"Saved combined plot for {test_name} to {plot_filename}")

if __name__ == '__main__':
    main()
