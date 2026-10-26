package com.millenairekingdoms.autonomy;

import net.minecraft.util.RandomSource;

/** Two independent opposed d20 rolls. Eligibility belongs to native military/political validation, never a die. */
public final class OpposedDice {
    public record Settings(double scale,int maximumModifier,int homeAdvantage){
        public static final Settings DEFAULT=new Settings(6,8,2);
        public Settings{if(!Double.isFinite(scale) || scale<0 || scale>12 || maximumModifier<0 || maximumModifier>12 || homeAdvantage<0 || homeAdvantage>8)throw new IllegalArgumentException("Invalid strategic dice balance");}
    }
    public record Roll(int attackDie,int defenseDie,int relativeModifier,int attackTotal,int defenseTotal){
        public Roll{if(attackDie<1 || attackDie>20 || defenseDie<1 || defenseDie>20 || Math.abs(relativeModifier)>12 || attackTotal<attackDie || defenseTotal<defenseDie)throw new IllegalArgumentException("Invalid committed strategic dice");}
        public boolean attackerWon(){return attackTotal>defenseTotal;}
    }
    private OpposedDice(){}
    public static int modifier(long attack,long defense,Settings settings){
        if(attack<=0 || defense<=0)throw new IllegalArgumentException("Verified positive native military power is required");
        double relative=settings.scale()*(Math.log((double)attack/defense)/Math.log(2));
        return (int)Math.max(-settings.maximumModifier(),Math.min(settings.maximumModifier(),Math.round(relative)));
    }
    public static Roll compare(long attack,long defense,int a,int d,Settings settings){
        int modifier=modifier(attack,defense,settings);return new Roll(a,d,modifier,a+Math.max(0,modifier),d+Math.max(0,-modifier)+settings.homeAdvantage());
    }
    public static Roll roll(long attack,long defense,RandomSource random,Settings settings){return compare(attack,defense,random.nextInt(20)+1,random.nextInt(20)+1,settings);}
    /** Exact probability over all400 equally likely independent d20 pairs, not a Monte Carlo approximation. */
    public static double attackWinProbability(long attack,long defense,Settings settings){int wins=0;for(int a=1;a<=20;a++)for(int d=1;d<=20;d++)if(compare(attack,defense,a,d,settings).attackerWon())wins++;return wins/400.0;}
}
