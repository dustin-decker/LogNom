import redis

r = redis.StrictRedis(host='172.17.0.2', port=6379, db=0)
list_name = 'stage1'

for num in range(0, 1000000):
    r.rpush(list_name, str(num))
