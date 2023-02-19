 local voucherId = ARGV[1]

 local userId = ARGV[2]

 local stockkey = 'seckill:stock:'

 local orderKey = 'seckill:order:'

 if(tonumber(redis.call('get',stockkey)) <= 0) then
     return 1
 end

 if(redi.call('sismember',orderKey,userId) == 1) then
     return 2
 end

 redis.call('incrby',stockkey,-1)

 redis.call('sadd',orderKey,userId)

 return 0
