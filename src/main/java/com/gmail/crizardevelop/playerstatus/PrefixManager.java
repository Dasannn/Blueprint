package com.gmail.crizardevelop.playerstatus;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class
PrefixManager extends JavaPlugin{
    /*
    public static String tierm4;
    public static String tierm3;
    public static String tierm2;
    public static String tierm1;
    public static String tier0;
    public static String tier1;
    public static String tier2;
    public static String tier3;
    public static String tier4;
    
    public static String namem4;
    public static String namem3;
    public static String namem2;
    public static String namem1;
    public static String name0;
    public static String name1;
    public static String name2;
    public static String name3;
    public static String name4;
    
    public static int repm4;
    public static int repm3;
    public static int repm2;
    public static int repm1;
    public static int rep0;
    public static int rep1;
    public static int rep2;
    public static int rep3;
    public static int rep4;
    
    
    private final main main;
    PrefixManager(main main){
        this.main = main;
    }
    
    public void loadStringPrefix(){
        tierm4 = main.getConfig().getString("tier-4.prefix").replace("&", "§");
        tierm3 = main.getConfig().getString("tier-3.prefix").replace("&", "§");
        tierm2 = main.getConfig().getString("tier-2.prefix").replace("&", "§");
        tierm1 = main.getConfig().getString("tier-1.prefix").replace("&", "§");
        tier0 = main.getConfig().getString("tier0.prefix").replace("&", "§");
        tier1 = main.getConfig().getString("tier1.prefix").replace("&", "§");
        tier2 = main.getConfig().getString("tier2.prefix").replace("&", "§");
        tier3 = main.getConfig().getString("tier3.prefix").replace("&", "§");
        tier4 = main.getConfig().getString("tier4.prefix").replace("&", "§");
    }
    
    public void loadStringNames(){
        namem4 = main.getConfig().getString("tier-4.name").replace("&", "§");
        namem3 = main.getConfig().getString("tier-3.name").replace("&", "§");
        namem2 = main.getConfig().getString("tier-2.name").replace("&", "§");
        namem1 = main.getConfig().getString("tier-1.name").replace("&", "§");
        name0 = main.getConfig().getString("tier0.name").replace("&", "§");
        name1 = main.getConfig().getString("tier1.name").replace("&", "§");
        name2 = main.getConfig().getString("tier2.name").replace("&", "§");
        name3 = main.getConfig().getString("tier3.name").replace("&", "§");
        name4 = main.getConfig().getString("tier4.name").replace("&", "§");
    }
    
    public void loadIntRep(){
        repm4 = main.getConfig().getInt("tier-4.name");
        repm3 = main.getConfig().getInt("tier-3.name");
        repm2 = main.getConfig().getInt("tier-2.name");
        repm1 = main.getConfig().getInt("tier-1.name");
        rep0 = main.getConfig().getInt("tier0.name");
        rep1 = main.getConfig().getInt("tier1.name");
        rep2 = main.getConfig().getInt("tier2.name");
        rep3 = main.getConfig().getInt("tier3.name");
        rep4 = main.getConfig().getInt("tier4.name");
    }
    
    public void updatePlayerPrefix(Player p){
        
        int playerReputation = main.getConfig().getInt("listPlayers."+p.getUniqueId()+".reputation");
        int typeStatus = 0;
        
        if(playerReputation == repm4){
            typeStatus = -4;
            main.getConfig().set("playerList"+p.getUniqueId()+".typeStatus", typeStatus);
            main.saveConfig();
        }
        if(playerReputation == repm3){
            typeStatus = -3;
            main.getConfig().set("playerList"+p.getUniqueId()+".typeStatus", typeStatus);
            main.saveConfig();
        }
        if(playerReputation == repm2){
            typeStatus = -2;
            main.getConfig().set("playerList"+p.getUniqueId()+".typeStatus", typeStatus);
            main.saveConfig();
        }
        if(playerReputation == repm1){
            typeStatus = -1;
            main.getConfig().set("playerList"+p.getUniqueId()+".typeStatus", typeStatus);
            main.saveConfig();
        }
        if(playerReputation == rep0){
            typeStatus = 0;
            main.getConfig().set("playerList"+p.getUniqueId()+".typeStatus", typeStatus);
            main.saveConfig();
        }
        if(playerReputation == rep1){
            typeStatus = 1;
            main.getConfig().set("playerList"+p.getUniqueId()+".typeStatus", typeStatus);
            main.saveConfig();
        }
        if(playerReputation == rep2){
            typeStatus = 2;
            main.getConfig().set("playerList"+p.getUniqueId()+".typeStatus", typeStatus);
            main.saveConfig();
        }
        if(playerReputation == rep3){
            typeStatus = 3;
            main.getConfig().set("playerList"+p.getUniqueId()+".typeStatus", typeStatus);
            main.saveConfig();
        }
        if(playerReputation == rep4){
            typeStatus = 4;
            main.getConfig().set("playerList"+p.getUniqueId()+".typeStatus", typeStatus);
            main.saveConfig();
        }
        
        
        
        switch (typeStatus){        
            case -4:
                p.setDisplayName(tierm4+ p.getName());
                p.setPlayerListName(tierm4+ p.getName());
            break;
            case -3:
                p.setDisplayName(tierm3+ p.getName());
                p.setPlayerListName(tierm3+ p.getName());
            break;
            case -2:
                p.setDisplayName(tierm2+ p.getName());
                p.setPlayerListName(tierm2+ p.getName());
            break;
            case -1:
                p.setDisplayName(tierm1+ p.getName());
                p.setPlayerListName(tierm1+ p.getName());
            break;
            case 0:
                p.setDisplayName(tier0+ p.getName());
                p.setPlayerListName(tier0+ p.getName());
            break;
            case 1:
                p.setDisplayName(tier1+ p.getName());
                p.setPlayerListName(tier1+ p.getName());
            break;
            case 2:
                p.setDisplayName(tier2+ p.getName());
                p.setPlayerListName(tier2+ p.getName());
            break;
            case 3:
                p.setDisplayName(tier3+ p.getName());
                p.setPlayerListName(tier3+ p.getName());
            break;
            case 4:
                p.setDisplayName(tier4+ p.getName());
                p.setPlayerListName(tier4+ p.getName());
            break;
        }
    }
    
    public void removeReputationPoint(Player p){
        int reputation = main.getConfig().getInt("playerList."+p.getUniqueId()+".reputation");
        main.getConfig().set("playerList."+p.getUniqueId()+"reputation", reputation-1);
    }
    
    public void addReputationPoint(Player p){
        int reputation = main.getConfig().getInt("playerList."+p.getUniqueId()+".reputation");
        main.getConfig().set("playerList."+p.getUniqueId()+"reputation", reputation+1);
        main.saveConfig();
    }
    
    public void setReputationPoint(Player p, int rep){
        main.getConfig().set("playerList."+p.getUniqueId()+"reputation", rep);
        main.saveConfig();
    }
    
    public void setDefaultConfig(Player p){
        if(!p.hasPlayedBefore()){
            main.getConfig().set("playerList."+p.getUniqueId()+".name", p.getName());
            main.getConfig().set("playerList."+p.getUniqueId()+"reputation", 0);
            main.getConfig().set("playerList."+p.getUniqueId()+"typeStatus", 0);
            main.saveConfig();
        }
    }
    
    public void showStatusInfo (Player sender, Player pinfo){
        sender.sendMessage(ChatColor.AQUA+"Status de "+pinfo.getName()+"\n"
                +ChatColor.YELLOW+"Name: "+ChatColor.GRAY+pinfo.getName()+"\n"
                +ChatColor.YELLOW+"Reputation: "+ChatColor.GRAY+main.getConfig().getInt("playerList."+pinfo.getUniqueId()+"reputation", 0)+"\n"
                +ChatColor.YELLOW+"Type Status: "+ChatColor.GRAY+main.getConfig().getInt("playerList."+pinfo.getUniqueId()+"typeStatus", 0)
        );
    }
    
    */
}
