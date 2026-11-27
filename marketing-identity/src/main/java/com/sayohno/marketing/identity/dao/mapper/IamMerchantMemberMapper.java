package com.sayohno.marketing.identity.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sayohno.marketing.identity.dao.entity.IamMerchantMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface IamMerchantMemberMapper extends BaseMapper<IamMerchantMember> {
    @Select("""
            SELECT m.* FROM iam_merchant_member m JOIN iam_merchant w ON w.id = m.merchant_id
            WHERE m.user_id = #{userId} AND m.status = 1 AND w.status = 1 AND m.role IN ('OWNER', 'OPERATOR')
            ORDER BY m.id
            """)
    List<IamMerchantMember> activeMemberships(long userId);
}
