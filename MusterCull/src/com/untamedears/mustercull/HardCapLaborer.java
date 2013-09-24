package com.untamedears.mustercull;

import org.bukkit.Chunk;
import org.bukkit.DyeColor;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;

/**
 * This class performs damage to mobs using the DAMAGE CullType.
 * @author ngozi
 */
public class HardCapLaborer extends Laborer {

	// Statistics
	private int _numberOfTimesExecuted = 0;
	private int _numberOfTimesExecutedWithPenaltyPurge = 0;
	private long _averageTimePerExecutionInMs = 0;
	private long _executionTimeForLastExecutionInMs = 0;
	
	
	/**
	 * Constructor which takes a reference to the main plug-in class.
	 * @param pluginInstance A reference to the main plug-in class.
	 */
	public HardCapLaborer(MusterCull pluginInstance) {
		super(pluginInstance);
	}

	/**
	 * Repeating method for the class.
     * Kills N mobs where N is the number of mobs over the mob cap.
     * Recently dead mobs are counted toward the mob cap since filtering them out is costly.
     * Therefore, this should not run too quickly (set config).
     *
     * If a lot of mobs recently died when this runs then this will kill too many mobs. Possible far too many.
     * Luckily this can only happen when the cap is lowered or old chunks are loaded.
	 */
	
	private static final Object lockObj = new Object();
	private static boolean currentlyRunning = false;
	
	public void run() {

        if (this.getPluginInstance().isPaused(GlobalCullType.HARDCAP)) {
            return;
        }
        
		synchronized(lockObj)
		{
			if(!currentlyRunning)
			{
				currentlyRunning = true;
			}
			else
			{
				return;
			}
			
		}

		int overHardMobLimit = getPluginInstance().overHardMobLimit();

		if (overHardMobLimit > 0) {
			
			long purgeTimeStart = System.currentTimeMillis();
			
			// Only console-warn if the number of mobs to cull is a fairly large-ish number.
			if (overHardMobLimit > 100)
			{
				this.getPluginInstance().getLogger().warning("Hard Cap Laborer - Exceptional Culling - Required to cull " + overHardMobLimit + " mobs.");
			}
			
			GlobalCullCullingStrategyType cullStrat = this.getPluginInstance().getGlobalCullingStrategy();
            
			int toKill = overHardMobLimit;
            
			if (cullStrat == GlobalCullCullingStrategyType.RANDOM)
			{
				// this.getPluginInstance().getLogger().warning("Hard Cap Laborer - Random based culling.");
				List<LivingEntity> mobs = getPluginInstance().getAllLivingNonPlayerMobs();
	            for (LivingEntity mob : mobs) {
	                if (! mob.isDead()) {
	                    toKill--;
	                    getPluginInstance().damageEntity(mob, 100);
	                }
	                if (toKill == 0)
	                    break;
	            }
			}
			else if(cullStrat == GlobalCullCullingStrategyType.PRIORITY)
			{
				// this.getPluginInstance().getLogger().warning("Hard Cap Laborer - Priority based culling.");
				toKill = HandleCullingBasedOnChunkConcentration(toKill);
	            toKill = HandleGlobalCulling(toKill);
			}
			else
			{
				this.getPluginInstance().getLogger().warning("Hard Cap Laborer - Cannot determine culling strategy, no work to do.");
			}
			
			_executionTimeForLastExecutionInMs = System.currentTimeMillis() - purgeTimeStart;
			_averageTimePerExecutionInMs = 
						(long) (_averageTimePerExecutionInMs * (_numberOfTimesExecuted / (_numberOfTimesExecuted + 1.)) 
						+ _executionTimeForLastExecutionInMs / (_numberOfTimesExecuted + 1.));
			_numberOfTimesExecuted++;
		}
		

		currentlyRunning = false;
		return;


	}
	
	public String GetStatisticDisplayString()
	{
		StringBuilder sb = new StringBuilder();

		sb.append("Hard Cap Cull Statistics:  \n");
		
		sb.append("Hard Cap Cull Execution count:  ");
		sb.append(_numberOfTimesExecuted);
		sb.append("\n");
		
		sb.append("Number of culls with penalty purges:  ");
		sb.append(_numberOfTimesExecutedWithPenaltyPurge);
		sb.append("\n");
		
		sb.append("Average time per execution:  ");
		sb.append(_averageTimePerExecutionInMs);
		sb.append(" milliseconds\n");
				
		sb.append("Execution time for last execution:  ");
		sb.append(_executionTimeForLastExecutionInMs);
		sb.append(" milliseconds\n");
		
		return sb.toString();
	}
	
	/**
	 * Given an integer mob to kill count, it will attempt to find if there are any problem chunks and begin a culling 
	 * @param mobCountToCull Amount of mobs we would like to kill in total - not the amount of mobs we have to kill just from this chunk based culling.
	 * @return How many mobs are left of the mobs we would like to kill.
	 */
	private int HandleCullingBasedOnChunkConcentration(int mobCountToCull)
	{
		List<LivingEntity> mobs = getPluginInstance().getAllLivingNonPlayerMobs();
		
        HashMap<Point, List<LivingEntity>> chunkEntities = new HashMap<Point, List<LivingEntity>>();
        int totalMobCount = 0;
        
        for (LivingEntity mob : mobs) {
        	 if ( ! mob.isDead()) {
        		 Chunk mobChunk = mob.getLocation().getChunk();
        		 Point d = new Point(mobChunk.getX(), mobChunk.getZ());
        		 
        		 if (!chunkEntities.containsKey(d))
        		 {
        			 chunkEntities.put(d, new ArrayList<LivingEntity>());
        		 }
        		 
        		 chunkEntities.get(d).add(mob);
        		 totalMobCount++;
        	 }
        }
        
        Point maxCenterPoint = null;
        int mobCountInLargestSuperChunk = -1;
        int maxChunksInMatchSet = 0;
                
        // For each chunk check all the chunks surrounding it.  3x3 and get mob counts.
        // Find the most populous super chunk.
        for(Point point : chunkEntities.keySet())
        {
        	int tempMobs = 0;
        	int tempChunkCount = 0;
        	            	
        	for(int x = -3; x <= 3; x++)
        	{
        		for (int z = -3; z <= 3; z++)
        		{
        			if (chunkEntities.containsKey(new Point(point.x - x, point.y - z)))
        			{
        				tempMobs += chunkEntities.get(new Point(point.x - x, point.y - z)).size();
        				tempChunkCount++;
        			}
        		}
        	}
        	
        	// If more mobs than our last super chunk, update it.
        	if (tempMobs > mobCountInLargestSuperChunk) 
        	{
        		maxCenterPoint = point;
        		mobCountInLargestSuperChunk = tempMobs;
        		maxChunksInMatchSet = tempChunkCount;
        	}
        }

        // Number of loaded chunks with mobs excepting the bad super chunk.
        int numberOfChunksToAverageOver = (chunkEntities.keySet().size()-maxChunksInMatchSet);
        
        // If the max mobs for a super chunk doesn't meet the penalty purge percent, early out.
        // Or if the 'penalty chunks' -is- all the chunks loaded.
        if ((mobCountInLargestSuperChunk / ((1.0) * totalMobCount) <= this.getPluginInstance().getHardCapCullingPriorityStrategyPenaltyMobPercent())
        		|| numberOfChunksToAverageOver == 0)
		{
        	return mobCountToCull;
		}
        
        // Log out the naughty chunk.
        this.getPluginInstance().getLogger().warning("Hard Cap Laborer - Found chunk that triggered a penalty purge based on mob count.  Chunk " + maxCenterPoint.x + ", " + maxCenterPoint.y + ".");
        _numberOfTimesExecutedWithPenaltyPurge++;

        int averageMobsPerChunk = (int) Math.ceil(totalMobCount / (1. * numberOfChunksToAverageOver));
        int superChunkMobCountToCull = mobCountInLargestSuperChunk - averageMobsPerChunk;
        
        superChunkMobCountToCull = superChunkMobCountToCull > mobCountToCull ? mobCountToCull : superChunkMobCountToCull;
        
        if (superChunkMobCountToCull <= 0)
        {
        	// It met the limit but its not more than the average, so do nothing.
        	// Only in special cases where loaded chunks are extremely few.
        	return mobCountToCull;
        }
        
        // Consider purging every mob in the chunks surrounding it.  7x7 with the superchunk at the center. 
        List<LivingEntity> mobsToConsiderPurging = new ArrayList<LivingEntity>();	  	
    	for(int x = -3; x <= 3; x++)
    	{
    		for (int z = -3; z <= 3; z++)
    		{
    			if (chunkEntities.containsKey(new Point(maxCenterPoint.x - x, maxCenterPoint.y - z)))
    			{
    				mobsToConsiderPurging.addAll(chunkEntities.get(new Point(maxCenterPoint.x - x, maxCenterPoint.y - z)));
    			}
    		}
    	}
    	
    	// We are only going to purge enough to bring this superchunk into a good status with our other chunks.
    	// Our unculled mobs, then, after this call would be our original mobCountToCull - the superCullCount + whats left over.
    	return mobCountToCull - superChunkMobCountToCull + PerformCullingLogic(mobsToConsiderPurging, superChunkMobCountToCull);
	}
	
	/**
	 * For a given set of candidates, we will first bring the candidates into proportion based on entity type (up to max items to cull) and will then round-robin remove them (up to max items to cull)
	 * @param cullCandidates The list of possible items that can be culled.
	 * @param maxItemsToCull Maximum items to cull on this run.
	 * @return The number of items remaining out of the orignal items-to-cull passed in.
	 */
	private int PerformCullingLogic(List<LivingEntity> cullCandidates, int maxItemsToCull)
	{
		if (maxItemsToCull == 0) return maxItemsToCull;
		
		// Step 1 - Categorize all items on our list into categories based on priority.
		List<LivingEntity> namedMobs = new ArrayList<LivingEntity>();
		List<LivingEntity> highValuePersistentMobs = new ArrayList<LivingEntity>();
		List<LivingEntity> medValuePersistentMobs = new ArrayList<LivingEntity>();
		List<LivingEntity> lowValuePersistentMobs = new ArrayList<LivingEntity>();
		List<LivingEntity> nonPersistentMobs = new ArrayList<LivingEntity>();
		
		for(LivingEntity mob : cullCandidates)
		{
			// Priority 1 - Named mob.
			if (null != mob.getCustomName() || mob.isCustomNameVisible()) 
			{ 
				namedMobs.add(mob); 
			}
			
			// Priority 2/3/4 - Persistent mobs.
			else if (!mob.getRemoveWhenFarAway())
			{
				// Priority 2 - High value persistent mobs (horses, villagers).
				// Golems specifically not added as they can be auto'ed leading to an unfortunate culling scenario.
				if (mob.getType() == EntityType.HORSE || mob.getType() == EntityType.VILLAGER)
				{
					highValuePersistentMobs.add(mob);
				}
				
				//Priority 3 - tame wolves, colored sheep, tame cats.
				else if (mob.getType() == EntityType.WOLF && ((org.bukkit.entity.Tameable)mob).isTamed())
				{
					medValuePersistentMobs.add(mob);
				}
				else if (mob.getType() == EntityType.OCELOT && ((org.bukkit.entity.Tameable)mob).isTamed())
				{
					medValuePersistentMobs.add(mob);
				}
				else if (mob.getType() == EntityType.SHEEP && ((org.bukkit.material.Colorable)mob).getColor() != DyeColor.WHITE)
				{
					medValuePersistentMobs.add(mob);
				}
				
				// Priority 4 - low value persistent mobs.
				else
				{
					lowValuePersistentMobs.add(mob);
				}
			}
			else
			{
				// Non Priority - non persistent mobs.
				nonPersistentMobs.add(mob);
			}
		}
		
		// Step 2 - Cull based on purge priorities.
		maxItemsToCull = purgeToEquivalentNumbers(maxItemsToCull, getListSortedByCounts(nonPersistentMobs));
		if (maxItemsToCull > 0) maxItemsToCull = purgeToEquivalentNumbers(maxItemsToCull, getListSortedByCounts(lowValuePersistentMobs));
		if (maxItemsToCull > 0) maxItemsToCull = purgeToEquivalentNumbers(maxItemsToCull, getListSortedByCounts(medValuePersistentMobs));
		if (maxItemsToCull > 0) maxItemsToCull = purgeToEquivalentNumbers(maxItemsToCull, getListSortedByCounts(highValuePersistentMobs));
		if (maxItemsToCull > 0) maxItemsToCull = purgeToEquivalentNumbers(maxItemsToCull, getListSortedByCounts(namedMobs));
		
		return maxItemsToCull;
	}

	/**
	 * For a given categorized entity list all of the same priority, we will attempt to bring them into proportion with one another and then round-robin remove (up to max items to cull).
	 * @param maxItemsToCull Maximum amount of items we can cull.
	 * @param orderedPurgeList 
	 * @return Of the maxItemsToCull originally passed in, how many remain to be culled.
	 */
	private int purgeToEquivalentNumbers(int maxItemsToCull, List<Entry<EntityType, List<LivingEntity>>> orderedPurgeList) {

		// Early out if we have no work to do.
		if (orderedPurgeList.size() <= 0 || maxItemsToCull <= 0)
		{
			return maxItemsToCull;
		}
		
		// Determine the average mob count per category.
		int totalItems = 0;
		for(int i = 0; i < orderedPurgeList.size(); i++)
		{
			totalItems += orderedPurgeList.get(i).getValue().size();
		}
		
		int average = totalItems / orderedPurgeList.size();
		
		// Iterate over all of the mob lists to remove (largest list first) and start removing items until it is equal to the average.
		// For each category, while we still have items left to cull.
		for(int i = 0; i < orderedPurgeList.size() && maxItemsToCull > 0; i++)
		{
			List<LivingEntity> purgeList = orderedPurgeList.get(i).getValue();

			// Even if the purgeList is already correctly-sized, we still want to always sort these lists.  Important in round-robin culling next.
			Collections.shuffle(purgeList);
			
			// Start removing mobs from this category.
			while(purgeList.size() > average && purgeList.size() > 0 && maxItemsToCull > 0)
			{
				LivingEntity mob = purgeList.remove(0);
				if (!mob.isDead())
				{
					// Cull it and remove it from our to-cull count.
					maxItemsToCull--;
					getPluginInstance().damageEntity(mob, 100);
				}
			}
		}
		
		
		// Now that we have made our categories approximately equivalent to each other, time to cull round-robin over these now-sorted lists.
		
		int ctr = 0;
		boolean removedAtLeastOneItemThisIteration = true;
		while(maxItemsToCull > 0)
		{
			// If the cycle has restarted.
			if (ctr == 0)
			{
				// Have we removed at least one item?  If not, bye!
				if (!removedAtLeastOneItemThisIteration)
				{
					// Control structure.
					break;
				}
				
				// Restart!
				removedAtLeastOneItemThisIteration = false;
			}
			
			// Kill off one item of this type.
			if (orderedPurgeList.get(ctr).getValue().size() > 0)
			{
				LivingEntity mob = orderedPurgeList.get(ctr).getValue().remove(0);
				if (!mob.isDead())
				{
					// Cull it and remove it from our to-cull count.
					maxItemsToCull--;
					getPluginInstance().damageEntity(mob, 100);

					// Note that we removed at least one mob this iteration.
					removedAtLeastOneItemThisIteration = true;
				}
			}
			
			// Move to next category and loop back if needed.
			ctr++;
			if (ctr >= orderedPurgeList.size() )
			{
				ctr = 0;
			}
			
		}
		
		return maxItemsToCull;
	}

	/**
	 * For a given unordered list of of mobs, we will attempt to categorize these and order this such that our largest mob categories come first. 
	 * @param mobList Unordered list of mobs.
	 * @return Ordered list of categorized mobs (categorized by entity types) such that the largest entity categories will come first.
	 */
	private List<Entry<EntityType, List<LivingEntity>>> getListSortedByCounts(List<LivingEntity> mobList) 
	{
		// Separate mobs by entity type.
		HashMap<EntityType, List<LivingEntity>> equivalentPurgeList = new HashMap<EntityType, List<LivingEntity>>();
		for(LivingEntity mob : mobList)
		{
			if(!equivalentPurgeList.containsKey(mob.getType()))
			{
				equivalentPurgeList.put(mob.getType(), new ArrayList<LivingEntity>());				
			}
			
			equivalentPurgeList.get(mob.getType()).add(mob);
		}
		
		// Sort these entity-types by the ones with the most first.
		List<Entry<EntityType, List<LivingEntity>>> orderedPurgeList = new LinkedList<Entry<EntityType, List<LivingEntity>>> (equivalentPurgeList.entrySet());
		Collections.sort(orderedPurgeList, Collections.reverseOrder(new Comparator<Entry<EntityType, List<LivingEntity>>>()
				{
					public int compare(Entry<EntityType, List<LivingEntity>> o1, Entry<EntityType, List<LivingEntity>> o2)
					{
						return Integer.compare(o1.getValue().size(), o2.getValue().size());
					}
				}
		));
		
		return orderedPurgeList;
	}

	/**
	 * Will perform categorized and proportional global culling in priority order.
	 * @param overHardMobLimit Max number of items to purge.
	 * @return 
	 */
	private int HandleGlobalCulling(int overHardMobLimit)
	{
		// Consider every mob in the world except players, and start culling.
		List<LivingEntity> mobList = getPluginInstance().getAllLivingNonPlayerMobs();
		
		return PerformCullingLogic(mobList, overHardMobLimit);
	}

}
