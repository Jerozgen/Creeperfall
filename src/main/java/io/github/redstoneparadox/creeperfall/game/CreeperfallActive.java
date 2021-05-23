package io.github.redstoneparadox.creeperfall.game;

import io.github.redstoneparadox.creeperfall.Creeperfall;
import io.github.redstoneparadox.creeperfall.entity.CreeperfallGuardianEntity;
import io.github.redstoneparadox.creeperfall.entity.CreeperfallOcelotEntity;
import io.github.redstoneparadox.creeperfall.entity.CreeperfallSkeletonEntity;
import io.github.redstoneparadox.creeperfall.game.config.CreeperfallConfig;
import io.github.redstoneparadox.creeperfall.game.map.CreeperfallMap;
import io.github.redstoneparadox.creeperfall.game.participant.CreeperfallParticipant;
import io.github.redstoneparadox.creeperfall.game.shop.CreeperfallShop;
import io.github.redstoneparadox.creeperfall.game.spawning.CreeperfallCreeperSpawnLogic;
import io.github.redstoneparadox.creeperfall.game.spawning.CreeperfallPlayerSpawnLogic;
import io.github.redstoneparadox.creeperfall.game.util.EntityTracker;
import io.github.redstoneparadox.creeperfall.game.util.Timer;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.EntityDamageSource;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.SkeletonEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.LiteralText;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.plasmid.game.GameSpace;
import xyz.nucleoid.plasmid.game.event.AttackEntityListener;
import xyz.nucleoid.plasmid.game.event.EntityDeathListener;
import xyz.nucleoid.plasmid.game.event.EntityDropLootListener;
import xyz.nucleoid.plasmid.game.event.EntityHitListener;
import xyz.nucleoid.plasmid.game.event.ExplosionListener;
import xyz.nucleoid.plasmid.game.event.GameCloseListener;
import xyz.nucleoid.plasmid.game.event.GameOpenListener;
import xyz.nucleoid.plasmid.game.event.GameTickListener;
import xyz.nucleoid.plasmid.game.event.OfferPlayerListener;
import xyz.nucleoid.plasmid.game.event.PlayerAddListener;
import xyz.nucleoid.plasmid.game.event.PlayerDamageListener;
import xyz.nucleoid.plasmid.game.event.PlayerDeathListener;
import xyz.nucleoid.plasmid.game.event.PlayerRemoveListener;
import xyz.nucleoid.plasmid.game.event.UseItemListener;
import xyz.nucleoid.plasmid.game.player.JoinResult;
import xyz.nucleoid.plasmid.game.player.PlayerSet;
import xyz.nucleoid.plasmid.game.rule.GameRule;
import xyz.nucleoid.plasmid.game.rule.RuleResult;
import xyz.nucleoid.plasmid.util.PlayerRef;
import xyz.nucleoid.plasmid.widget.GlobalWidgets;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

public class CreeperfallActive {
    private final CreeperfallConfig config;

    public final GameSpace gameSpace;
    private final CreeperfallMap gameMap;
    private final Random random = new Random();

    // TODO replace with ServerPlayerEntity if players are removed upon leaving
    private final EntityTracker tracker;
    private final Object2ObjectMap<PlayerRef, CreeperfallParticipant> participants;
    private final CreeperfallPlayerSpawnLogic playerSpawnLogic;
    private final CreeperfallCreeperSpawnLogic creeperSpawnLogic;
    private final CreeperfallStageManager stageManager;
    private final boolean ignoreWinState;
    private final CreeperfallTimerBar timerBar;
    private final Timer arrowReplenishTimer;

    private CreeperfallActive(GameSpace gameSpace, CreeperfallMap map, GlobalWidgets widgets, CreeperfallConfig config, Set<PlayerRef> participants) {
        this.gameSpace = gameSpace;
        this.config = config;
        this.gameMap = map;
        this.tracker = new EntityTracker();
        this.playerSpawnLogic = new CreeperfallPlayerSpawnLogic(gameSpace, map, config);
        this.creeperSpawnLogic = new CreeperfallCreeperSpawnLogic(gameSpace, this, map, config, tracker);
        this.participants = new Object2ObjectOpenHashMap<>();

        for (PlayerRef player : participants) {
            this.participants.put(player, new CreeperfallParticipant(player, gameSpace, config));
        }

        this.stageManager = new CreeperfallStageManager();
        this.ignoreWinState = this.participants.size() <= 1;
        this.timerBar = new CreeperfallTimerBar(widgets);
        int arrowReplenishTime = config.arrowReplenishTimeSeconds * 20;
        this.arrowReplenishTimer = Timer.createRepeating(arrowReplenishTime, this::onReplenishArrows);
    }

    public static void open(GameSpace gameSpace, CreeperfallMap map, CreeperfallConfig config) {
        gameSpace.openGame(game -> {
            Set<PlayerRef> participants = gameSpace.getPlayers().stream()
                    .map(PlayerRef::of)
                    .collect(Collectors.toSet());
            GlobalWidgets widgets = new GlobalWidgets(game);
            CreeperfallActive active = new CreeperfallActive(gameSpace, map, widgets, config, participants);

            game.setRule(GameRule.CRAFTING, RuleResult.DENY);
            game.setRule(GameRule.PORTALS, RuleResult.DENY);
            game.setRule(GameRule.PVP, RuleResult.DENY);
            game.setRule(GameRule.HUNGER, RuleResult.DENY);
            game.setRule(GameRule.FALL_DAMAGE, RuleResult.DENY);
            game.setRule(GameRule.BLOCK_DROPS, RuleResult.DENY);
            game.setRule(GameRule.THROW_ITEMS, RuleResult.DENY);
            game.setRule(GameRule.UNSTABLE_TNT, RuleResult.DENY);
            game.setRule(GameRule.BREAK_BLOCKS, RuleResult.DENY);

            game.on(GameOpenListener.EVENT, active::onOpen);
            game.on(GameCloseListener.EVENT, active::onClose);

            game.on(OfferPlayerListener.EVENT, player -> JoinResult.ok());
            game.on(PlayerAddListener.EVENT, active::addPlayer);
            game.on(PlayerRemoveListener.EVENT, active::removePlayer);

            game.on(GameTickListener.EVENT, active::tick);
            game.on(ExplosionListener.EVENT, active::onExplosion);
            game.on(EntityDeathListener.EVENT, active::onEntityDeath);
            game.on(EntityDropLootListener.EVENT, active::onDropLoot);
            game.on(UseItemListener.EVENT, active::onUseItem);

            game.on(PlayerDamageListener.EVENT, active::onPlayerDamage);
            game.on(PlayerDeathListener.EVENT, active::onPlayerDeath);
            game.on(AttackEntityListener.EVENT, active::onAttackEntity);
            game.on(EntityHitListener.EVENT, active::onEntityHit);
        });
    }

    public void announceStage(int stage) {
        PlayerSet players = gameSpace.getPlayers();
        players.sendTitle(new TranslatableText("game.creeperfall.stage", stage), 5, 10, 5);
    }

    public void spawnGuardian() {
        ServerWorld world = gameSpace.getWorld();
        CreeperfallGuardianEntity entity = new CreeperfallGuardianEntity(world);

        entity.setInvulnerable(true);
        spawnEntity(entity, 0.5, 68, 0.5, SpawnReason.SPAWN_EGG);
    }

    public void spawnOcelot() {
        ServerWorld world = gameSpace.getWorld();
        CreeperfallOcelotEntity entity = new CreeperfallOcelotEntity(tracker, world);

        entity.setInvulnerable(true);
        spawnEntity(entity, 0.5, 65, 0.5, SpawnReason.SPAWN_EGG);
    }

    public void spawnSkeleton() {
        ServerWorld world = gameSpace.getWorld();
        CreeperfallSkeletonEntity entity = new CreeperfallSkeletonEntity(world);

        spawnEntity(entity, 0.5, 65, 0.5, SpawnReason.SPAWN_EGG);
    }

    public void spawnEntity(Entity entity, double x, double y, double z, SpawnReason spawnReason) {
        ServerWorld world = gameSpace.getWorld();

        if (gameSpace.getWorld() != entity.world) {
            Creeperfall.LOGGER.error("Attempted to add an entity to Creeperfall's gamespace that was not in the correct ServerWorld.");
            return;
        }

        Objects.requireNonNull(entity).setPos(x, y, z);
        entity.updatePosition(x, y, z);
        entity.setVelocity(Vec3d.ZERO);

        entity.prevX = x;
        entity.prevY = y;
        entity.prevZ = z;

        if (entity instanceof MobEntity) {
            ((MobEntity) entity).initialize(world, world.getLocalDifficulty(new BlockPos(0, 0, 0)), spawnReason, null, null);
        }

        world.spawnEntity(entity);
        tracker.add(entity);
    }

    private void onReplenishArrows() {
        for (CreeperfallParticipant participant: participants.values()) {
            participant.replenishArrows();
        }
    }

    private void onOpen() {
        ServerWorld world = this.gameSpace.getWorld();
        for (PlayerRef ref : this.participants.keySet()) {
            ref.ifOnline(world, this::spawnParticipant);
        }
        this.stageManager.onOpen(world.getTime(), this.config);
        // TODO setup logic
    }

    private void onClose() {
        // TODO teardown logic
    }

    private void addPlayer(ServerPlayerEntity player) {
        if (!this.participants.containsKey(PlayerRef.of(player))) {
            this.spawnSpectator(player);
        }
    }

    private void removePlayer(ServerPlayerEntity player) {
        this.participants.remove(PlayerRef.of(player));
    }

    private ActionResult onPlayerDamage(ServerPlayerEntity player, DamageSource source, float amount) {
        Entity sourceEntity = source.getSource();

        if (sourceEntity instanceof ArrowEntity) {
            Entity owner = ((ArrowEntity) sourceEntity).getOwner();

            if (owner instanceof SkeletonEntity) {
                return ActionResult.FAIL;
            }
        }

        // TODO handle damage
        //this.spawnParticipant(player);
        return ActionResult.PASS;
    }

    private ActionResult onPlayerDeath(ServerPlayerEntity player, DamageSource source) {
        this.removePlayer(player);
        this.spawnSpectator(player);
        return ActionResult.FAIL;
    }

    private ActionResult onAttackEntity(ServerPlayerEntity attacker, Hand hand, Entity attacked, EntityHitResult hitResult) {
        if (!(attacked instanceof CreeperEntity)) return ActionResult.FAIL;
        return ActionResult.PASS;
    }

    private ActionResult onEntityHit(ProjectileEntity entity, EntityHitResult hitResult) {
        if (!(hitResult.getEntity() instanceof CreeperEntity)) return ActionResult.FAIL;
        return ActionResult.PASS;
    }

    private void spawnParticipant(ServerPlayerEntity player) {
        this.playerSpawnLogic.resetPlayer(player, GameMode.SURVIVAL, false);
        this.playerSpawnLogic.spawnPlayer(player);
    }

    private void spawnSpectator(ServerPlayerEntity player) {
        this.playerSpawnLogic.resetPlayer(player, GameMode.SPECTATOR, false);
        this.playerSpawnLogic.spawnPlayer(player);
    }

    private void tick() {
        tracker.clean();
        boolean finishedEarly = participants.isEmpty();

        ServerWorld world = this.gameSpace.getWorld();
        long time = world.getTime();

        if (finishedEarly) {
            long remainingTime = this.stageManager.finishTime - world.getTime();
            if (remainingTime >= 0) this.stageManager.finishEarly(remainingTime);
        }

        CreeperfallStageManager.IdleTickResult result = this.stageManager.tick(time, gameSpace);

        switch (result) {
            case CONTINUE_TICK:
                break;
            case TICK_FINISHED:
                return;
            case GAME_STARTED:
                for (CreeperfallParticipant participant: participants.values()) {
                    participant.notifyOfStart();
                    participant.replenishArrows();
                }
                return;
            case GAME_FINISHED:
                this.broadcastResult(this.checkWinResult());
                return;
            case GAME_CLOSED:
                this.gameSpace.close();
                return;
        }

        if (finishedEarly) {
            this.timerBar.update(0, this.config.timeLimitSecs * 20);
        }
        else {
            this.timerBar.update(this.stageManager.finishTime - time, this.config.timeLimitSecs * 20);
            creeperSpawnLogic.tick();
            arrowReplenishTimer.tick();
        }
    }

    private void onExplosion(List<BlockPos> affectedBlocks) {
        affectedBlocks.clear();
    }

    private ActionResult onEntityDeath(LivingEntity entity, DamageSource source) {
        if (entity instanceof CreeperEntity && source instanceof EntityDamageSource) {
            @Nullable Entity sourceEntity = source.getSource();
            @Nullable ServerPlayerEntity player = null;

            if (sourceEntity instanceof ServerPlayerEntity && gameSpace.containsEntity(sourceEntity)) {
                player = (ServerPlayerEntity) sourceEntity;
            }
            else if (sourceEntity instanceof ArrowEntity) {
                Entity owner = ((ArrowEntity)sourceEntity).getOwner();

                if (owner instanceof ServerPlayerEntity && gameSpace.containsEntity(owner)) {
                    player = (ServerPlayerEntity) owner;
                }
            }

            if (player != null) {
                int maxEmeralds = config.emeraldRewardCount.getMax();
                int minEmeralds = config.emeraldRewardCount.getMin();
                int emeralds = (random.nextInt(maxEmeralds - minEmeralds) + 1) + minEmeralds;
                player.giveItemStack(new ItemStack(Items.EMERALD, emeralds));
                player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.MASTER, 1.0f, 1.0f);
            }
        }

        return ActionResult.PASS;
    }

    private TypedActionResult<List<ItemStack>> onDropLoot(LivingEntity dropper, List<ItemStack> loot) {
        loot.clear();
        return TypedActionResult.consume(loot);
    }

    private TypedActionResult<ItemStack> onUseItem(ServerPlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);

        if (stack.getItem() == Items.COMPASS) {
            player.openHandledScreen(CreeperfallShop.create(participants.get(PlayerRef.of(player)), this, config.shopConfig));
            return TypedActionResult.success(stack);
        }

        return TypedActionResult.pass(stack);
    }

    private void broadcastResult(GameResult result) {
        boolean survivors = result.survivors;

        Text message;
        SoundEvent sound;
        if (survivors) {
            ServerWorld world = gameSpace.getWorld();
            List<CreeperfallParticipant> survivorsList = new ArrayList<>(participants.values());


            if (survivorsList.size() == 1) {
                ServerPlayerEntity playerEntity = survivorsList.get(0).getPlayer().getEntity(world);
                assert playerEntity != null;
                message = new TranslatableText("game.creeperfall.end.success.one", playerEntity.getDisplayName().shallowCopy());
            }
            else if (survivorsList.size() == 2) {
                ServerPlayerEntity playerEntityOne = survivorsList.get(0).getPlayer().getEntity(world);
                ServerPlayerEntity playerEntityTwo = survivorsList.get(1).getPlayer().getEntity(world);
                assert playerEntityOne != null;
                assert playerEntityTwo != null;
                message = new TranslatableText("game.creeperfall.end.success.multiple", playerEntityOne.getDisplayName().shallowCopy(), playerEntityTwo.getDisplayName().shallowCopy());
            }
            else {
                List<CreeperfallParticipant> firstSurvivorsList = survivorsList.subList(0, survivorsList.size() - 1);
                MutableText survivorsText = new LiteralText("");

                for (CreeperfallParticipant survivor: firstSurvivorsList) {
                    ServerPlayerEntity playerEntity = survivor.getPlayer().getEntity(world);
                    assert playerEntity != null;
                    survivorsText.append(playerEntity.getDisplayName().shallowCopy());
                    survivorsText.append(", ");
                }

                String stringifiedSurvivors = survivorsText.asString();
                stringifiedSurvivors = stringifiedSurvivors.substring(0, stringifiedSurvivors.length() - 1);

                ServerPlayerEntity playerEntityLast = survivorsList.get(survivorsList.size() - 1).getPlayer().getEntity(world);
                assert playerEntityLast != null;
                message = new TranslatableText("game.creeperfall.end.success.multiple", stringifiedSurvivors, playerEntityLast.getDisplayName().shallowCopy());
            }

            sound = SoundEvents.ENTITY_VILLAGER_CELEBRATE;

        } else {
            message = new TranslatableText("game.creeperfall.end.fail").formatted(Formatting.RED);
            sound = SoundEvents.ENTITY_VILLAGER_NO;
        }

        PlayerSet players = this.gameSpace.getPlayers();
        players.sendMessage(message);
        players.sendSound(sound);
    }

    private GameResult checkWinResult() {
        if (participants.isEmpty()) {
            return GameResult.no();
        }

        return GameResult.survived();
    }

    static class GameResult {
        private final boolean survivors;

        private GameResult(boolean survivors) {
            this.survivors = survivors;
        }

        static GameResult no() {
            return new GameResult(false);
        }

        static GameResult survived() {
            return new GameResult(true);
        }

        public boolean isSurvivors() {
            return this.survivors;
        }
    }
}
