import redis

r = redis.StrictRedis(host='172.17.0.2', port=6379, db=0)
list_name = 'stage2'

for num in range(0, 5):
    print(r.blpop(list_name))