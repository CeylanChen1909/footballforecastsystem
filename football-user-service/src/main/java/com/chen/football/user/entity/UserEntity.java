package com.chen.football.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("t_user")
public class UserEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    /** 登录账号，注册用户使用邮箱；历史账号可暂时为空。 */
    private String email;
    /** 对外展示昵称，兼容旧数据时回退到 username。 */
    private String nickname;
    /** 头像 data URL；仅接受受限的 PNG/JPEG/WebP 内容，演示环境无需额外文件存储。 */
    @TableField("avatar_data")
    private String avatarData;
    @TableField("nickname_updated_at")
    private LocalDateTime nicknameUpdatedAt;
    @TableField("email_verified")
    private Boolean emailVerified;
    private String passwordHash;
    private String role;
    private String status;
    @TableField("created_at")
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getAvatarData() { return avatarData; }
    public void setAvatarData(String avatarData) { this.avatarData = avatarData; }
    public LocalDateTime getNicknameUpdatedAt() { return nicknameUpdatedAt; }
    public void setNicknameUpdatedAt(LocalDateTime nicknameUpdatedAt) { this.nicknameUpdatedAt = nicknameUpdatedAt; }
    public Boolean getEmailVerified() { return emailVerified; }
    public void setEmailVerified(Boolean emailVerified) { this.emailVerified = emailVerified; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
