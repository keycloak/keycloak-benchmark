import argparse
import asyncio
import datetime
import time
from typing import Optional

import requests
from playwright.async_api import async_playwright


async def generate_pdf(
        target_url: str,
        auth_token: Optional[str],
        destination_file: str,
        page_width: int = 1920,
        margin: int = 80
) -> None:

    async with async_playwright() as p:
        browser = await p.chromium.launch()
        page = await browser.new_page(device_scale_factor=4)  # You can change the scale_factor to +/- images quality
        page.set_default_navigation_timeout(120000.0)
        await page.set_viewport_size({'width': page_width, 'height': 1080})
        await page.set_extra_http_headers(
            {'Authorization': f'Bearer {auth_token}'}
        )
        await page.goto(target_url)
        await page.wait_for_selector('.react-grid-layout')
        page_height = await page.evaluate(
            'document.getElementsByClassName(\'react-grid-layout\')[0].getBoundingClientRect().bottom;'
        )
        await page.set_viewport_size({'width': page_width, 'height': page_height})
        await page.wait_for_load_state('networkidle')
        print(f"Navigated to the page, taking PDF now and saving it to {destination_file}")
        await page.pdf(
            path=destination_file,
            scale=1,
            width=f'{page_width + margin * 2}px',
            height=f'{page_height + margin * 2}px',
            display_header_footer=False,
            print_background=True,
            margin={
                'top': f'{margin}px',
                'bottom': f'{margin}px',
                'left': f'{margin}px',
                'right': f'{margin}px',
            }
        )
        time.sleep(1)
        await browser.close()


async def generate_grafana_api_token(dashboard_domain, admin_password):
    print(f"CREATE GRAFANA API KEY")
    dttm = get_current_time()
    url =  f'http://admin:{admin_password}@{dashboard_domain}/api/auth/keys'
    reqBody = {'name':f'dashboard_pdf_{dttm}','role':'Editor','secondsToLive':86400}
    auth_token_id = ""
    auth_token = ""
    try:
        response = requests.post(url, json = reqBody)
        response.raise_for_status()
        jsonResponse = response.json()
        #only uncomment to help with debugging, dont push this to a source control repo :)
        #print(jsonResponse["id"], jsonResponse["key"])
        auth_token_id = jsonResponse["id"]
        auth_token = jsonResponse["key"]

    except requests.exceptions.HTTPError as http_err:
        print(f'HTTP error {http_err.args[0]} occured: {http_err}')
    except Exception as err:
        print(f'Other error occured: {err}')

    return (auth_token_id,auth_token)

async def delete_grafana_api_key(dashboard_domain, admin_password, auth_token_id):
    print(f"DELETE GRAFANA API KEY")
    url =  f'http://admin:{admin_password}@{dashboard_domain}/api/auth/keys/{auth_token_id}'
    try:
        response = requests.delete(url)
        response.raise_for_status()
        jsonResponse = response.json()
        print(jsonResponse)

    except HTTPError as http_err:
        print(f'HTTP error occured: {http_err}')
    except Exception as err:
        print(f'Other error occured: {err}')

def get_current_time():
    now = datetime.datetime.now()
    timestamp = now.strftime("%Y%m%d_%H:%M:%S")
    return timestamp

async def main():

    dttm = get_current_time()

    parser = argparse.ArgumentParser()
    parser.add_argument("--grafana_domain", help='grafana domain without "http://" ')
    parser.add_argument("--admin_password", help='Grafana Auth Token')
    parser.add_argument("--time_window", help='ex: from=1694700191047&to=1694700510513')
    parser.add_argument("--keycloak_namespace", help='default is runner-keycloak', default="runner-keycloak")

    args = parser.parse_args()
    admin_password: str = args.admin_password
    grafana_domain: str = args.grafana_domain
    keycloak_namespace: str = args.keycloak_namespace
    time_window: str = args.time_window

    #generate token
    auth_token = await generate_grafana_api_token(grafana_domain, admin_password)

    dashboards = ['basic-keycloak-dashboard-by-namespace/keycloak-perf-tests', 'Ct9jmJyVz/keycloak-infinispan-board', 'R3kK_894z/authentication-code-slo', 'A0kQZ7u4k/client-credentials-slo']
    for dashboard in dashboards:
        dashboard_uid = dashboard.split("/")[-1]

        #conditionally setting the args in the dashboard urls
        keycloak_perf_tests_url: str =  f'http://{grafana_domain}/d/{dashboard}?orgId=1&var-namespace={keycloak_namespace}&{time_window}'

        if(dashboard_uid == 'authentication-code-slo' or dashboard_uid == 'client-credentials-slo'):
            keycloak_perf_tests_url = keycloak_perf_tests_url+f'&var-pod_name=All'
        elif(dashboard_uid == 'keycloak-infinispan-board'):
            keycloak_perf_tests_url = keycloak_perf_tests_url+f'&var-local_cache=All&var-distributed_cache=All'

        #defining the dashboard pdf file name
        destination_file: str = f'./grafana_report_pdfs/{dttm}/{dashboard.split("/")[-1]}.pdf'

        #print pdf
        await generate_pdf(keycloak_perf_tests_url, auth_token[1], destination_file)

    #delete token
    await delete_grafana_api_key(grafana_domain, admin_password, auth_token[0])

asyncio.run(main())
