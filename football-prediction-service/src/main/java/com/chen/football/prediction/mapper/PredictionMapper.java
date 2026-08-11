package com.chen.football.prediction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chen.football.prediction.entity.PredictionEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PredictionMapper extends BaseMapper<PredictionEntity> {
}

