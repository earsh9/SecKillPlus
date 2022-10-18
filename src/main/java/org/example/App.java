package org.example;

import org.example.dao.UserDaoMapper;
import org.example.entity.UserDao;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication(scanBasePackages = {"org.example"})
@RestController
@MapperScan("org.example.dao")
public class App 
{
    @Autowired
    private UserDaoMapper userDaoMapper;

    @RequestMapping("/")
    public String home() {
        UserDao userDao = userDaoMapper.selectByPrimaryKey(1);
        if (userDao == null) {
            return "用户对象不存在";
        } else {
            return userDao.getName();
        }
    }

    public static void main( String[] args )
    {
//        System.out.println( "Hello World!" );
        SpringApplication.run(App.class, args);
    }
}
