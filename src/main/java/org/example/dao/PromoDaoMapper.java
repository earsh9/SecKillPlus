package org.example.dao;

import org.apache.ibatis.annotations.Mapper;
import org.example.entity.PromoDao;

@Mapper
public interface PromoDaoMapper {
    //根据商品id获得秒杀商品信息
    PromoDao selectByItemId(Integer itemId);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table promo
     *
     * @mbggenerated Sat Jul 23 23:49:12 CST 2022
     */
    int deleteByPrimaryKey(Integer id);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table promo
     *
     * @mbggenerated Sat Jul 23 23:49:12 CST 2022
     */
    int insert(PromoDao record);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table promo
     *
     * @mbggenerated Sat Jul 23 23:49:12 CST 2022
     */
    int insertSelective(PromoDao record);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table promo
     *
     * @mbggenerated Sat Jul 23 23:49:12 CST 2022
     */
    PromoDao selectByPrimaryKey(Integer id);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table promo
     *
     * @mbggenerated Sat Jul 23 23:49:12 CST 2022
     */
    int updateByPrimaryKeySelective(PromoDao record);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table promo
     *
     * @mbggenerated Sat Jul 23 23:49:12 CST 2022
     */
    int updateByPrimaryKey(PromoDao record);
}