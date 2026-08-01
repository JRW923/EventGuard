package com.eventguard.auth.model;

import java.util.List;

/** 角色实体（含已分配权限码）。 */
public class Role {

    private Long id;
    private String code;
    private String name;
    private String description;
    private List<String> permissions = List.of();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getPermissions() { return permissions; }
    public void setPermissions(List<String> permissions) { this.permissions = permissions; }
}
