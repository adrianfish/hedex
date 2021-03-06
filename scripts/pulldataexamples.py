import requests
from zeep import Client

server_url = 'http://localhost'
username = 'hedex-api-user'
password = 'hedex'

client = Client(server_url + '/sakai-ws/soap/login?wsdl')
session_id = client.service.login(username, password)

print('Session ID: %s' % session_id)

start_date = '2018-06-20'

if session_id:
    print("\nGetting engagement activity ...")
    r = requests.get(server_url + '/direct/hedex-api/Get_Retention_Engagement_EngagementActivity?RequestingAgent=noodlebus&sessionid=' + session_id + '&startDate=' + start_date)
    print(r.json())

    print("\nGetting assignments ...")
    r = requests.get(server_url + '/direct/hedex-api/Get_Retention_Engagement_Assignments?RequestingAgent=noodlebus&sessionid=' + session_id + '&startDate=' + start_date)
    print(r.json())

    print("\nGetting attendance ...")
    r = requests.get(server_url + '/direct/hedex-api/Get_Retention_Engagement_Attendance?RequestingAgent=noodlebus&sessionid=' + session_id + '&startDate=' + start_date)
    print(r.json())
