package com.sayohno.marketing.common.outbox;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConsumerInboxMapper extends BaseMapper<ConsumerInbox> {
}
