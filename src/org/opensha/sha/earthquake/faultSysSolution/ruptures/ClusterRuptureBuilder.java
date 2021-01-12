package org.opensha.sha.earthquake.faultSysSolution.ruptures;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.dom4j.DocumentException;
import org.opensha.commons.util.ExceptionUtils;
import org.opensha.commons.util.FaultUtils;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.PlausibilityConfiguration;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.PlausibilityConfiguration.Builder;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.PlausibilityFilter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.CumulativeProbabilityFilter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.JumpAzimuthChangeFilter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.ParentCoulombCompatibilityFilter.Directionality;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.CumulativePenaltyFilter.*;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.CumulativeProbabilityFilter.*;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.strategies.ClusterConnectionStrategy;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.strategies.ClusterPermutationStrategy;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.strategies.DistCutoffClosestSectClusterConnectionStrategy;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.strategies.SectCountAdaptivePermutationStrategy;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.strategies.ConnectionPointsPermutationStrategy;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.strategies.UCERF3ClusterConnectionStrategy;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.strategies.UCERF3ClusterPermuationStrategy;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.util.FilterDataClusterRupture;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.util.SectionDistanceAzimuthCalculator;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.util.UniqueRupture;
import org.opensha.sha.faultSurface.FaultSection;
import org.opensha.sha.simulators.stiffness.AggregatedStiffnessCache;
import org.opensha.sha.simulators.stiffness.AggregatedStiffnessCalculator;
import org.opensha.sha.simulators.stiffness.SubSectStiffnessCalculator;
import org.opensha.sha.simulators.stiffness.AggregatedStiffnessCalculator.AggregationMethod;
import org.opensha.sha.simulators.stiffness.SubSectStiffnessCalculator.PatchAlignment;
import org.opensha.sha.simulators.stiffness.SubSectStiffnessCalculator.StiffnessType;

import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Range;
import com.google.common.primitives.Ints;

import scratch.UCERF3.FaultSystemRupSet;
import scratch.UCERF3.enumTreeBranches.DeformationModels;
import scratch.UCERF3.enumTreeBranches.FaultModels;
import scratch.UCERF3.enumTreeBranches.ScalingRelationships;
import scratch.UCERF3.inversion.coulomb.CoulombRates;
import scratch.UCERF3.inversion.laughTest.PlausibilityResult;
import scratch.UCERF3.utils.DeformationModelFetcher;
import scratch.UCERF3.utils.FaultSystemIO;

/**
 * Code to recursively build ClusterRuptures, applying any rupture plausibility filters
 * @author kevin
 *
 */
public class ClusterRuptureBuilder {
	
	private List<FaultSubsectionCluster> clusters;
	private List<PlausibilityFilter> filters;
	private int maxNumSplays = 0;
	
	private RupDebugCriteria debugCriteria;
	private boolean stopAfterDebugMatch;
	
	/**
	 * Constructor which gets everything from the PlausibilityConfiguration
	 * 
	 * @param configuration plausibilty configuration
	 */
	public ClusterRuptureBuilder(PlausibilityConfiguration configuration) {
		this(configuration.getConnectionStrategy().getClusters(), configuration.getFilters(),
				configuration.getMaxNumSplays());
	}
	
	/**
	 * Constructor which uses previously built clusters (with connections added)
	 * 
	 * @param clusters list of clusters (with connections added)
	 * @param filters list of plausibility filters
	 * @param maxNumSplays the maximum number of splays per rupture (use 0 to disable splays)
	 */
	public ClusterRuptureBuilder(List<FaultSubsectionCluster> clusters,
			List<PlausibilityFilter> filters, int maxNumSplays) {
		this.clusters = clusters;
		this.filters = filters;
		this.maxNumSplays = maxNumSplays;
	}
	
	/**
	 * This allows you to debug the rupture building process. It will print out a lot of details
	 * if the given criteria are satisfied.
	 * 
	 * @param debugCriteria criteria for which to print debug information
	 * @param stopAfterMatch if true, building will cease immediately after a match is found 
	 */
	public void setDebugCriteria(RupDebugCriteria debugCriteria, boolean stopAfterMatch) {
		this.debugCriteria = debugCriteria;
		this.stopAfterDebugMatch = stopAfterMatch;
	}
	
	private class RupSizeTracker {
		private int largestRup;
		private int largestRupPrintMod = 10;
		private HashSet<UniqueRupture> allPassedUniques;
		
		public RupSizeTracker() {
			this.largestRup = 0;
			this.largestRupPrintMod = 10;
			this.allPassedUniques = new HashSet<>();
		}
		
		public synchronized void processPassedRupture(ClusterRupture rup) {
			if (!allPassedUniques.contains(rup.unique)) {
				allPassedUniques.add(rup.unique);
				int count = rup.getTotalNumSects();
				if (count > largestRup) {
					largestRup = count;
					if (largestRup % largestRupPrintMod == 0)
						System.out.println("\tNew largest rup has "+largestRup
								+" subsections with "+rup.getTotalNumJumps()+" jumps and "
								+rup.splays.size()+" splays. "+countDF.format(allPassedUniques.size())
								+" total unique passing ruptures found");
				}
			}
		}
	}
	
	/**
	 * This builds ruptures using the given cluster permutation strategy
	 * 
	 * @param permutationStrategy strategy for determining unique & viable subsection permutations 
	 * for each cluster 
	 * @return list of unique ruptures which were build
	 */
	public List<ClusterRupture> build(ClusterPermutationStrategy permutationStrategy) {
		return build(permutationStrategy, 1);
	}
	
	/**
	 * This builds ruptures using the given cluster permutation strategy with the given number of threads
	 * 
	 * @param permutationStrategy strategy for determining unique & viable subsection permutations 
	 * for each cluster 
	 * @param numThreads
	 * @return list of unique ruptures which were build
	 */
	public List<ClusterRupture> build(ClusterPermutationStrategy permutationStrategy, int numThreads) {
		List<ClusterRupture> rups = new ArrayList<>();
		HashSet<UniqueRupture> uniques = new HashSet<>();
		RupSizeTracker track = new RupSizeTracker();
		
		if (numThreads <= 1) {
			for (FaultSubsectionCluster cluster : clusters) {
				ClusterBuildCallable build = new ClusterBuildCallable(
						permutationStrategy, cluster, uniques, track, null);
				try {
					build.call();
				} catch (Exception e) {
					throw ExceptionUtils.asRuntimeException(e);
				}
				try {
					build.merge(rups);
				} catch (InterruptedException | ExecutionException e) {
					throw ExceptionUtils.asRuntimeException(e);
				}
				if (build.debugStop)
					break;
//				for (FaultSection startSection : cluster.subSects) {
//					for (FaultSubsectionCluster permutation : permutationStrategy.getPermutations(
//							cluster, startSection)) {
//						ClusterRupture rup = new ClusterRupture(permutation);
//						PlausibilityResult result = testRup(rup, false);
//						if (debugCriteria != null && debugCriteria.isMatch(rup)
//								&& debugCriteria.appliesTo(result)) {
//							System.out.println("\tPermutation "+permutation+" result="+result);
//							testRup(rup, true);
//							if (stopAfterDebugMatch) {
//								return rups;
//							}
//						}
//						if (!result.canContinue())
//							// stop building here
//							continue;
//						if (result.isPass()) {
//							// passes as is, add it if it's new
//							if (!uniques.contains(rup.unique)) {
//								rups.add(rup);
//								uniques.add(rup.unique); // will add in merge below
//								int count = rup.getTotalNumSects();
//								if (count > largestRup) {
//									largestRup = count;
//									if (largestRup % largestRupPrintMod == 0)
//										System.out.println("\tNew largest rup has "+largestRup
//												+" subsections with "+rup.getTotalNumJumps()+" jumps and "
//												+rup.splays.size()+" splays. "+rups.size()+" rups in total");
//								}
//							}
//						}
//						// continue to build this rupture
//						boolean canContinue = addRuptures(rups, uniques, rup, rup, 
//								result.isPass(), permutationStrategy);
//						if (!canContinue) {
//							System.out.println("Stopping due to debug criteria match with "+rups.size()+" ruptures");
//							return rups;
//						}
//					}
//				}
			}
		} else {
			// multi threaded
			ExecutorService exec = Executors.newFixedThreadPool(numThreads);
			
			List<Future<ClusterBuildCallable>> futures = new ArrayList<>();
			
			for (FaultSubsectionCluster cluster : clusters) {
				ClusterBuildCallable build = new ClusterBuildCallable(
						permutationStrategy, cluster, uniques, track, exec);
				futures.add(exec.submit(build));
			}
			
			System.out.println("Waiting on "+futures.size()+" cluster build futures");
			for (Future<ClusterBuildCallable> future : futures) {
				ClusterBuildCallable build;
				try {
					build = future.get();
				} catch (Exception e) {
					throw ExceptionUtils.asRuntimeException(e);
				}
				try {
					build.merge(rups);
				} catch (InterruptedException | ExecutionException e) {
					throw ExceptionUtils.asRuntimeException(e);
				}
				if (build.debugStop) {
					exec.shutdownNow();
					break;
				}
			}
			
			exec.shutdown();
		}
		
		
		return rups;
	}
	
	private static DecimalFormat countDF = new DecimalFormat("#");
	static {
		countDF.setGroupingUsed(true);
		countDF.setGroupingSize(3);
	}
	
	private class ClusterBuildCallable implements Callable<ClusterBuildCallable> {
		
		private ClusterPermutationStrategy permutationStrategy;
		private FaultSubsectionCluster cluster;
		private HashSet<UniqueRupture> uniques;
		private List<Future<List<ClusterRupture>>> rupListFutures;
		private boolean debugStop = false;
		private RupSizeTracker track;
		private ExecutorService exec;

		public ClusterBuildCallable(ClusterPermutationStrategy permutationStrategy,
				FaultSubsectionCluster cluster, HashSet<UniqueRupture> uniques, RupSizeTracker track,
				ExecutorService exec) {
			this.permutationStrategy = permutationStrategy;
			this.cluster = cluster;
			this.uniques = uniques;
			this.track = track;
			this.exec = exec;
		}

		@Override
		public ClusterBuildCallable call() throws Exception {
			if (this.exec != null)
				rupListFutures = Collections.synchronizedList(new ArrayList<>());
			else
				rupListFutures = new ArrayList<>();
			for (FaultSection startSection : cluster.subSects) {
				for (FaultSubsectionCluster permutation : permutationStrategy.getPermutations(
						cluster, startSection)) {
					ClusterRupture rup = new FilterDataClusterRupture(permutation);
//					ClusterRupture rup = new ClusterRupture(permutation);
					PlausibilityResult result = testRup(rup, false);
					if (debugCriteria != null && debugCriteria.isMatch(rup)
							&& debugCriteria.appliesTo(result)) {
						System.out.println("\tPermutation "+permutation+" result="+result);
						testRup(rup, true);
						if (stopAfterDebugMatch) {
							debugStop = true;
							return this;
						}
					}
					if (!result.canContinue())
						// stop building here
						continue;
					if (result.isPass()) {
						// passes as is, add it if it's new
						track.processPassedRupture(rup);
						if (!uniques.contains(rup.unique))
							// this means that this rupture passes and has not yet been processed
//							rups.add(rup);
							rupListFutures.add(new FakeRupListFuture(Collections.singletonList(rup)));
					}
					// continue to build this rupture
					List<ClusterRupture> rups = new ArrayList<>();
					boolean canContinue = addRuptures(rups, uniques, rup, rup, 
							permutationStrategy, track, exec, rupListFutures);
					if (!rups.isEmpty())
						rupListFutures.add(new FakeRupListFuture(rups));
					if (!canContinue) {
						System.out.println("Stopping due to debug criteria match with "+rups.size()+" ruptures");
						debugStop = true;
						return this;
					}
				}
			}
			return this;
		}
		
		public void merge(List<ClusterRupture> masterRups) throws InterruptedException, ExecutionException {
			int added = 0;
			int raw = 0;
			for (Future<List<ClusterRupture>> future : rupListFutures) {
				for (ClusterRupture rup : future.get()) {
					if (!uniques.contains(rup.unique)) {
						masterRups.add(rup);
						uniques.add(rup.unique);
						// make sure that contains now returns true
						Preconditions.checkState(uniques.contains(rup.unique));
//						Preconditions.checkState(uniques.contains(rup.reversed().unique));
						added++;
						raw++;
					}
				}
			}
			System.out.println("Merged in "+countDF.format(masterRups.size())+" ruptures after processing "
					+ "cluster "+cluster.parentSectionID+": "+cluster.parentSectionName
					+" ("+added+" new, "+raw+" incl. possible duplicates). "
					+countDF.format(track.allPassedUniques.size())+" total unique passing ruptures found");
		}
		
	}
	
	private class FakeRupListFuture implements Future<List<ClusterRupture>> {
		
		private List<ClusterRupture> ruptures;

		public FakeRupListFuture(List<ClusterRupture> ruptures) {
			this.ruptures = ruptures;
			
		}

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			return false;
		}

		@Override
		public boolean isCancelled() {
			return false;
		}

		@Override
		public boolean isDone() {
			return true;
		}

		@Override
		public List<ClusterRupture> get() throws InterruptedException, ExecutionException {
			return ruptures;
		}

		@Override
		public List<ClusterRupture> get(long timeout, TimeUnit unit)
				throws InterruptedException, ExecutionException, TimeoutException {
			return ruptures;
		}
		
	}
	
	private PlausibilityResult testRup(ClusterRupture rupture, final boolean debug) {
		PlausibilityResult result = PlausibilityResult.PASS;
		for (PlausibilityFilter filter : filters) {
			PlausibilityResult filterResult = filter.apply(rupture, debug);
			if (debug)
				System.out.println("\t\t"+filter.getShortName()+": "+filterResult);
			result = result.logicalAnd(filterResult);
			if (!result.canContinue() && !debug)
				break;
		}
		return result;
	}
	
	private class AddRupturesCallable implements Callable<List<ClusterRupture>> {
		
		private HashSet<UniqueRupture> uniques;
		private ClusterRupture currentRupture;
		private ClusterRupture currentStrand;
		private ClusterPermutationStrategy permutationStrategy;
		private RupSizeTracker track;
		private Jump jump;

		public AddRupturesCallable(HashSet<UniqueRupture> uniques,
				ClusterRupture currentRupture, ClusterRupture currentStrand,
				ClusterPermutationStrategy permutationStrategy, RupSizeTracker track, Jump jump) {
			this.uniques = uniques;
			this.currentRupture = currentRupture;
			this.currentStrand = currentStrand;
			this.permutationStrategy = permutationStrategy;
			this.track = track;
			this.jump = jump;
		}

		@Override
		public List<ClusterRupture> call() {
			List<ClusterRupture> rups = new ArrayList<>();
			addJumpPermutations(rups, uniques, currentRupture, currentStrand,
					permutationStrategy, jump, track);
			return rups;
		}
		
	}
	
	private boolean addRuptures(List<ClusterRupture> rups, HashSet<UniqueRupture> uniques,
			ClusterRupture currentRupture, ClusterRupture currentStrand,
			ClusterPermutationStrategy permutationStrategy, RupSizeTracker track,
			ExecutorService exec, List<Future<List<ClusterRupture>>> futures) {
		FaultSubsectionCluster lastCluster = currentStrand.clusters[currentStrand.clusters.length-1];
		FaultSection firstSection = currentStrand.clusters[0].startSect;

		// try to grow this strand first
		for (FaultSection endSection : lastCluster.endSects) {
			for (Jump jump : lastCluster.getConnections(endSection)) {
				if (!currentRupture.contains(jump.toSection)) {
					if (exec != null) {
						// fork it
						futures.add(exec.submit(new AddRupturesCallable(uniques, currentRupture, currentStrand,
								permutationStrategy, track, jump)));
					} else {
						boolean canContinue = addJumpPermutations(rups, uniques, currentRupture, currentStrand,
								permutationStrategy, jump, track);
						if (!canContinue)
							return false;
					}
				}
			}
		}
		
		// now try to add splays
		if (currentStrand == currentRupture && currentRupture.splays.size() < maxNumSplays) {
			for (FaultSubsectionCluster cluster : currentRupture.clusters) {
				for (FaultSection section : cluster.subSects) {
					if (section.equals(firstSection))
						// can't jump from the first section of the rupture
						continue;
					if (lastCluster.endSects.contains(section))
						// this would be a continuation of the main rupture, not a splay
						break;
					for (Jump jump : cluster.getConnections(section)) {
						if (!currentRupture.contains(jump.toSection)) {
							if (exec != null) {
								// fork it
								futures.add(exec.submit(new AddRupturesCallable(uniques, currentRupture, currentStrand,
										permutationStrategy, track, jump)));
							} else {
								boolean canContinue = addJumpPermutations(rups, uniques, currentRupture, currentStrand,
										permutationStrategy, jump, track);
								if (!canContinue)
									return false;
							}
						}
					}
				}
			}
		}
		return true;
	}

	private boolean addJumpPermutations(List<ClusterRupture> rups, HashSet<UniqueRupture> uniques,
			ClusterRupture currentRupture, ClusterRupture currentStrand,
			ClusterPermutationStrategy permutationStrategy, Jump jump, RupSizeTracker track) {
		Preconditions.checkNotNull(jump);
		for (FaultSubsectionCluster permutation : permutationStrategy.getPermutations(
				currentRupture, jump.toCluster, jump.toSection)) {
			boolean hasLoopback = false;
			for (FaultSection sect : permutation.subSects) {
				if (currentRupture.contains(sect)) {
					// this permutation contains a section already part of this rupture, stop
					hasLoopback = true;
					break;
				}
			}
			if (hasLoopback)
				continue;
			Preconditions.checkState(permutation.startSect.equals(jump.toSection));
			Jump testJump = new Jump(jump.fromSection, jump.fromCluster,
					jump.toSection, permutation, jump.distance);
			ClusterRupture candidateRupture = currentRupture.take(testJump);
			PlausibilityResult result = testRup(candidateRupture, false);
			boolean debugMatch = debugCriteria != null && debugCriteria.isMatch(currentRupture, testJump)
					&& debugCriteria.appliesTo(result);
			if (debugMatch) {
				System.out.println("Debug match with result="+result);
				System.out.println("\tMulti "+currentRupture+" => "+testJump.toCluster);
				System.out.println("Testing full:");
				testRup(candidateRupture, true);
				if (stopAfterDebugMatch)
					return false;
			}
			if (!result.canContinue()) {
				if (debugMatch)
					System.out.println("Can't continue, bailing");
				// stop building this permutation
				continue;
			}
			if (result.isPass()) {
				// passes as is, add it if it's new
				track.processPassedRupture(candidateRupture);
				if (!uniques.contains(candidateRupture.unique)) {
					if (debugMatch)
						System.out.println("We passed and this is potentially new, adding");
					rups.add(candidateRupture);
				} else if (debugMatch)
					System.out.println("We passed but have already processed this rupture, skipping");
			}
			// continue to build this rupture
			ClusterRupture newCurrentStrand;
			if (currentStrand == currentRupture) {
				newCurrentStrand = candidateRupture;
			} else {
				// we're building a splay, try to continue that one
				newCurrentStrand = null;
				for (ClusterRupture splay : candidateRupture.splays.values()) {
					if (splay.contains(jump.toSection)) {
						newCurrentStrand = splay;
						break;
					}
				}
				Preconditions.checkNotNull(newCurrentStrand);
				FaultSection newLastStart = newCurrentStrand.clusters[newCurrentStrand.clusters.length-1].startSect;
				Preconditions.checkState(newLastStart.equals(permutation.startSect));
			}
			boolean canContinue = addRuptures(rups, uniques, candidateRupture, newCurrentStrand,
					permutationStrategy, track, null, null);
			if (!canContinue)
				return false;
		}
		return true;
	}
	
	public static interface RupDebugCriteria {
		public boolean isMatch(ClusterRupture rup);
		public boolean isMatch(ClusterRupture rup, Jump newJump);
		public boolean appliesTo(PlausibilityResult result);
	}
	
	public static class StartEndSectRupDebugCriteria implements RupDebugCriteria {
		
		private int startSect;
		private int endSect;
		private boolean parentIDs;
		private boolean failOnly;

		public StartEndSectRupDebugCriteria(int startSect, int endSect, boolean parentIDs, boolean failOnly) {
			this.startSect = startSect;
			this.endSect = endSect;
			this.parentIDs = parentIDs;
			this.failOnly = failOnly;
		}

		@Override
		public boolean isMatch(ClusterRupture rup) {
			if (startSect >= 0 && !isMatch(rup.clusters[0].startSect, startSect))
				return false;
			FaultSubsectionCluster lastCluster = rup.clusters[rup.clusters.length-1];
			if (endSect >= 0 && !isMatch(
					lastCluster.subSects.get(lastCluster.subSects.size()-1), endSect))
				return false;
			return true;
		}

		@Override
		public boolean isMatch(ClusterRupture rup, Jump newJump) {
			if (startSect >= 0 && !isMatch(rup.clusters[0].startSect, startSect))
				return false;
			if (endSect >= 0 && !isMatch(
					newJump.toCluster.subSects.get(newJump.toCluster.subSects.size()-1), endSect))
				return false;
			return true;
		}
		
		private boolean isMatch(FaultSection sect, int id) {
			if (parentIDs)
				return sect.getParentSectionId() == id;
			return sect.getSectionId() == id;
		}

		@Override
		public boolean appliesTo(PlausibilityResult result) {
			if (failOnly)
				return !result.isPass();
			return true;
		}
		
	}
	
	public static class ParentSectsRupDebugCriteria implements RupDebugCriteria {
		
		private boolean failOnly;
		private boolean allowAdditional;
		private int[] parentIDs;

		public ParentSectsRupDebugCriteria(boolean failOnly, boolean allowAdditional, int... parentIDs) {
			this.failOnly = failOnly;
			this.allowAdditional = allowAdditional;
			this.parentIDs = parentIDs;
		}
		
		private HashSet<Integer> getParents(ClusterRupture rup) {
			HashSet<Integer> parents = new HashSet<>();
			for (FaultSubsectionCluster cluster : rup.clusters)
				parents.add(cluster.parentSectionID);
			for (ClusterRupture splay : rup.splays.values())
				parents.addAll(getParents(splay));
			return parents;
		}
		
		private boolean test(HashSet<Integer> parents) {
			if (!allowAdditional && parents.size() != parentIDs.length)
				return false;
			for (int parentID : parentIDs)
				if (!parents.contains(parentID))
					return false;
			return true;
		}

		@Override
		public boolean isMatch(ClusterRupture rup) {
			return test(getParents(rup));
		}

		@Override
		public boolean isMatch(ClusterRupture rup, Jump newJump) {
			HashSet<Integer> parents = getParents(rup);
			parents.add(newJump.toCluster.parentSectionID);
			return test(parents);
		}

		@Override
		public boolean appliesTo(PlausibilityResult result) {
			if (failOnly)
				return !result.isPass();
			return true;
		}
		
	}
	
	public static class SectsRupDebugCriteria implements RupDebugCriteria {
		
		private boolean failOnly;
		private boolean allowAdditional;
		private int[] sectIDs;

		public SectsRupDebugCriteria(boolean failOnly, boolean allowAdditional, int... sectIDs) {
			this.failOnly = failOnly;
			this.allowAdditional = allowAdditional;
			this.sectIDs = sectIDs;
		}
		
		private HashSet<Integer> getSects(ClusterRupture rup) {
			HashSet<Integer> sects = new HashSet<>();
			for (FaultSubsectionCluster cluster : rup.clusters)
				for (FaultSection sect : cluster.subSects)
					sects.add(sect.getSectionId());
			for (ClusterRupture splay : rup.splays.values())
				sects.addAll(getSects(splay));
			return sects;
		}
		
		private boolean test(HashSet<Integer> sects) {
			if (!allowAdditional && sects.size() != sectIDs.length)
				return false;
			for (int sectID : sectIDs)
				if (!sects.contains(sectID))
					return false;
			return true;
		}

		@Override
		public boolean isMatch(ClusterRupture rup) {
			return test(getSects(rup));
		}

		@Override
		public boolean isMatch(ClusterRupture rup, Jump newJump) {
			HashSet<Integer> sects = getSects(rup);
			for (FaultSection sect : newJump.toCluster.subSects)
				sects.add(sect.getSectionId());
			return test(sects);
		}

		@Override
		public boolean appliesTo(PlausibilityResult result) {
			if (failOnly)
				return !result.isPass();
			return true;
		}
		
	}
	
	private static int[] loadRupString(String rupStr, boolean parents) {
		Preconditions.checkState(rupStr.contains("["));
		List<Integer> ids = new ArrayList<>();
		while (rupStr.contains("[")) {
			rupStr = rupStr.substring(rupStr.indexOf("[")+1);
			Preconditions.checkState(rupStr.contains(":"));
			if (parents) {
				String str = rupStr.substring(0, rupStr.indexOf(":"));
				ids.add(Integer.parseInt(str));
			} else {
				rupStr = rupStr.substring(rupStr.indexOf(":")+1);
				Preconditions.checkState(rupStr.contains("]"));
				String str = rupStr.substring(0, rupStr.indexOf("]"));
				String[] split = str.split(",");
				for (String idStr : split)
					ids.add(Integer.parseInt(idStr));
			}
		}
		return Ints.toArray(ids);
	}
	
	public static class CompareRupSetNewInclusionCriteria implements RupDebugCriteria {
		
		private HashSet<UniqueRupture> uniques;
		
		public CompareRupSetNewInclusionCriteria(FaultSystemRupSet rupSet) {
			uniques = new HashSet<>();
			for (List<Integer> rupSects : rupSet.getSectionIndicesForAllRups())
				uniques.add(UniqueRupture.forIDs(rupSects));
		}

		@Override
		public boolean isMatch(ClusterRupture rup) {
			return !uniques.contains(rup.unique);
		}

		@Override
		public boolean isMatch(ClusterRupture rup, Jump newJump) {
			return !uniques.contains(new UniqueRupture(rup.unique, newJump.toCluster));
		}

		@Override
		public boolean appliesTo(PlausibilityResult result) {
			return result.isPass();
		}
		
	}
	
	public static class CompareRupSetExclusionCriteria implements RupDebugCriteria {
		
		private HashSet<UniqueRupture> uniques;
		
		public CompareRupSetExclusionCriteria(FaultSystemRupSet rupSet) {
			uniques = new HashSet<>();
			for (List<Integer> rupSects : rupSet.getSectionIndicesForAllRups())
				uniques.add(UniqueRupture.forIDs(rupSects));
		}

		@Override
		public boolean isMatch(ClusterRupture rup) {
			return uniques.contains(rup.unique);
		}

		@Override
		public boolean isMatch(ClusterRupture rup, Jump newJump) {
			return uniques.contains(new UniqueRupture(rup.unique, newJump.toCluster));
		}

		@Override
		public boolean appliesTo(PlausibilityResult result) {
			return !result.isPass();
		}
		
	}
	
	public static class ResultCriteria implements RupDebugCriteria {
		
		private PlausibilityResult[] results;

		public ResultCriteria(PlausibilityResult... results) {
			this.results = results;
			
		}

		@Override
		public boolean isMatch(ClusterRupture rup) {
			return true;
		}

		@Override
		public boolean isMatch(ClusterRupture rup, Jump newJump) {
			return true;
		}

		@Override
		public boolean appliesTo(PlausibilityResult result) {
			for (PlausibilityResult r : results)
				if (r == result)
					return true;
			return false;
		}
		
	}

	@SuppressWarnings("unused")
	public static void main(String[] args) throws IOException, DocumentException {
		File rupSetsDir = new File("/home/kevin/OpenSHA/UCERF4/rup_sets");
		FaultModels fm = FaultModels.FM3_1;
		File distAzCacheFile = new File(rupSetsDir, fm.encodeChoiceString().toLowerCase()
				+"_dist_az_cache.csv");
		DeformationModels dm = fm.getFilterBasis();
		ScalingRelationships scale = ScalingRelationships.MEAN_UCERF3;
		
		DeformationModelFetcher dmFetch = new DeformationModelFetcher(fm, dm,
				null, 0.1);
		
		List<? extends FaultSection> subSects = dmFetch.getSubSectionList();
		
		RupDebugCriteria debugCriteria = null;
		boolean stopAfterDebug = false;
		
//		RupDebugCriteria debugCriteria = new ResultCriteria(PlausibilityResult.PASS);
//		boolean stopAfterDebug = false;
		
//		RupDebugCriteria debugCriteria = new ParentSectsRupDebugCriteria(false, false, 672, 668);
////		RupDebugCriteria debugCriteria = new StartEndSectRupDebugCriteria(672, -1, true, false);
//		boolean stopAfterDebug = true;

//		FaultSystemRupSet compRupSet = FaultSystemIO.loadRupSet(new File(
//				"/home/kevin/workspace/OpenSHA/dev/scratch/UCERF3/data/scratch/InversionSolutions/"
//				+ "2013_05_10-ucerf3p3-production-10runs_COMPOUND_SOL_FM3_1_MEAN_BRANCH_AVG_SOL.zip"));
//		System.out.println("Loaded "+compRupSet.getNumRuptures()+" comparison ruptures");
//		RupDebugCriteria debugCriteria = new CompareRupSetNewInclusionCriteria(compRupSet);
//		boolean stopAfterDebug = true;
		
//		RupDebugCriteria debugCriteria = new SectsRupDebugCriteria(false, false,
//				1639, 1640, 1641, 1642, 1643);
//		boolean stopAfterDebug = true;
		
//		String rupStr = "[219:14,13][220:1217,1216,1215,1214][184:345][108:871,870][199:1404][130:1413,1412]";
//		RupDebugCriteria debugCriteria = new SectsRupDebugCriteria(false, false,
//				loadRupString(rupStr, false));
//		boolean stopAfterDebug = true;
		
//		RupDebugCriteria debugCriteria = new ParentSectsRupDebugCriteria(false, false, 219, 220, 184, 108, 240);
//		boolean stopAfterDebug = false;

		SectionDistanceAzimuthCalculator distAzCalc = new SectionDistanceAzimuthCalculator(subSects);
		if (distAzCacheFile.exists()) {
			System.out.println("Loading dist/az cache from "+distAzCacheFile.getAbsolutePath());
			distAzCalc.loadCacheFile(distAzCacheFile);
		}
		int numAzCached = distAzCalc.getNumCachedAzimuths();
		int numDistCached = distAzCalc.getNumCachedDistances();
		
		/*
		 * =============================
		 * To reproduce UCERF3
		 * =============================
		 */
//		PlausibilityConfiguration config = PlausibilityConfiguration.getUCERF3(subSects, distAzCalc, fm);
//		ClusterPermutationStrategy permStrat = new UCERF3ClusterPermuationStrategy();
//		String outputName = fm.encodeChoiceString().toLowerCase()+"_reproduce_ucerf3.zip";
//		AggregatedStiffnessCache stiffnessCache = null;
//		File stiffnessCacheFile = null;
//		int stiffnessCacheSize = 0;
		
		/*
		 * =============================
		 * For other experiments
		 * =============================
		 */
		// build stiffness calculator (used for new Coulomb)
		SubSectStiffnessCalculator stiffnessCalc = new SubSectStiffnessCalculator(
				subSects, 2d, 3e4, 3e4, 0.5);
		stiffnessCalc.setPatchAlignment(PatchAlignment.FILL_OVERLAP);
		AggregatedStiffnessCache stiffnessCache = stiffnessCalc.getAggregationCache(StiffnessType.CFF);
		File stiffnessCacheFile = new File(rupSetsDir, stiffnessCache.getCacheFileName());
		int stiffnessCacheSize = 0;
		if (stiffnessCacheFile.exists())
			stiffnessCacheSize = stiffnessCache.loadCacheFile(stiffnessCacheFile);
		
		/*
		 * Connection strategy: which faults are allowed to connect, and where?
		 */
		// use this for the exact same connections as UCERF3
		double minJumpDist = 5d;
		ClusterConnectionStrategy connectionStrategy =
				new UCERF3ClusterConnectionStrategy(subSects,
						distAzCalc, minJumpDist, CoulombRates.loadUCERF3CoulombRates(fm));
		// use this for simpler connection rules
//		ClusterConnectionStrategy connectionStrategy =
//			new DistCutoffClosestSectClusterConnectionStrategy(subSects, distAzCalc, minJumpDist);
		
		String outputName = fm.encodeChoiceString().toLowerCase();
		if (minJumpDist != 5d)
			outputName += "_"+new DecimalFormat("0.#").format(minJumpDist)+"km";
		Builder configBuilder = PlausibilityConfiguration.builder(connectionStrategy, subSects);
		
		/*
		 * Plausibility filters: which ruptures (utilizing those connections) are allowed?
		 */
		// UCERF3 filters
//		configBuilder.u3All(CoulombRates.loadUCERF3CoulombRates(fm)); outputName += "_ucerf3";
		configBuilder.minSectsPerParent(2, true, true); // always do this one
//		configBuilder.u3Cumulatives(); outputName += "_u3Cml"; // cml rake and azimuth
//		configBuilder.cumulativeAzChange(560f); outputName += "_cmlAz"; // cml azimuth only
//		configBuilder.u3Azimuth(); outputName += "_u3Az";
//		configBuilder.u3Coulomb(CoulombRates.loadUCERF3CoulombRates(fm)); outputName += "_u3CFF";
		
		// new probability-based cumulative filter (cumulatives should always be first for efficiency)
//		float probThresh = 0.005f;
//		outputName += "_cmlProb"+(float)probThresh;
////		List<RuptureProbabilityCalc> probCalcs = new ArrayList<>();
////		probCalcs.add(new BiasiWesnousky2016CombJumpDistProb(1d)); outputName += "-BW16Dist";
////		probCalcs.add(new BiasiWesnousky2017JumpAzChangeProb(distAzCalc)); outputName += "-BW17Az";
////		probCalcs.add(new BiasiWesnousky2017MechChangeProb()); outputName += "-BW17Mech";
////		configBuilder.cumulativeProbability(probThresh, probCalcs.toArray(new RuptureProbabilityCalc[0]));
//		configBuilder.cumulativeProbability(probThresh,
//				CumulativeProbabilityFilter.getPrefferedBWCalcs(distAzCalc)); outputName += "-BW16-17";
		
		// new penalty-based cumulative filter (cumulatives should always be first for efficiency)
//		float penThresh = 10f;
//		outputName += "_cmlPen"+new DecimalFormat("0.#").format(penThresh);
//		boolean noDoubleCount = false;
//		if (noDoubleCount)
//			outputName += "-noDblCnt";
//		List<Penalty> penalties = new ArrayList<>();
////		penalties.add(new JumpPenalty(3f, 2d, false)); outputName += "-2xJump3km";
////		penalties.add(new JumpPenalty(1f, 1d, false)); outputName += "-jump1km";
//		penalties.add(new JumpPenalty(0f, 1d, true)); outputName += "-jumpScalar1x";
//		penalties.add(new RakeChangePenalty(45f, 2d, false)); outputName += "-2xrake45";
//		penalties.add(new DipChangePenalty(20, 1d, false)); outputName += "-dip20";
////		penalties.add(new AzimuthChangePenalty(20f, 1d, false,
////				new JumpAzimuthChangeFilter.SimpleAzimuthCalc(distAzCalc))); outputName += "-az20";
//		penalties.add(new AzimuthChangePenalty(0f, 6d/180d, true,
//				new JumpAzimuthChangeFilter.SimpleAzimuthCalc(distAzCalc))); outputName += "-azScale6";
//		configBuilder.cumulativePenalty(penThresh, noDoubleCount, penalties.toArray(new Penalty[0]));
		
		// other cumulatives
//		configBuilder.cumulativeRakeChange(180f); outputName += "_cmlRake";
//		configBuilder.netRupCoulomb(stiffnessCalc, StiffnessAggregationMethod.MEDIAN, 0.75f,
//				RupCoulombQuantity.MEAN_SECT_FRACT_POSITIVES); outputName += "_cffNetFract0.75";
//		AggregatedStiffnessCalculator aggNetPatchFracts =
//				AggregatedStiffnessCalculator.builder(StiffnessType.CFF, stiffnessCalc)
//				.receiverPatchAgg(AggregationMethod.SUM).sectToSectAgg(AggregationMethod.FRACT_POSITIVE)
//				.sectsToSectsAgg(AggregationMethod.MEAN).get();
//		configBuilder.netRupCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, true,
//				AggregationMethod.SUM, AggregationMethod.PASSTHROUGH,
//				AggregationMethod.RECEIVER_SUM, AggregationMethod.FRACT_POSITIVE), 0.95f); outputName += "_cffPatchNetFract0.95";
//		configBuilder.netRupCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, true,
//				AggregationMethod.NUM_POSITIVE, AggregationMethod.SUM, AggregationMethod.HALF_INTERACTIONS,
//				AggregationMethod.FRACT_POSITIVE), 0.95f); outputName += "_cffSectHalfPos0.95";
//		configBuilder.netRupCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, true,
//				AggregationMethod.NUM_POSITIVE, AggregationMethod.SUM, AggregationMethod.HALF_INTERACTIONS,
//				AggregationMethod.FRACT_POSITIVE), 0.95f); outputName += "_cffSectHalfPos0.95";
		// fraction of receiver patches with >3/4 of interactions positive
//		configBuilder.netRupCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, true,
//				AggregationMethod.NUM_POSITIVE, AggregationMethod.SUM, AggregationMethod.THREE_QUARTER_INTERACTIONS,
//				AggregationMethod.FRACT_POSITIVE), 0.90f); outputName += "_cff3_4_f0.9Sects";
//		configBuilder.netRupCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, true,
//				AggregationMethod.NUM_POSITIVE, AggregationMethod.SUM, AggregationMethod.HALF_INTERACTIONS,
//				AggregationMethod.NUM_NEGATIVE), Range.atMost(3f)); outputName += "_cffMax3NegSects";
//		configBuilder.netRupCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, true,
//				AggregationMethod.NUM_POSITIVE, AggregationMethod.SUM, AggregationMethod.HALF_INTERACTIONS,
//				AggregationMethod.NUM_NEGATIVE), Range.lessThan(1f)); outputName += "_cffNoNegSects";
		configBuilder.netRupCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, true,
				AggregationMethod.NUM_POSITIVE, AggregationMethod.SUM, AggregationMethod.SUM, AggregationMethod.THREE_QUARTER_INTERACTIONS),
				Range.greaterThan(0f)); outputName += "_cff3_4_IntsPos";
//		configBuilder.netRupCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, true,
//				AggregationMethod.NUM_POSITIVE, AggregationMethod.SUM, AggregationMethod.SUM, AggregationMethod.NINE_TENTH_INTERACTIONS),
//				Range.greaterThan(0f)); outputName += "_cff9_10_IntsPos";
		float cmlProb = 0.02f;
		boolean probFullRup = false;
		boolean probAllowNeg = true;
		configBuilder.cumulativeProbability(cmlProb, new RelativeCoulombProb(
				new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, false,
				AggregationMethod.FLATTEN, AggregationMethod.SUM, AggregationMethod.SUM, AggregationMethod.SUM),
				connectionStrategy, probFullRup, probAllowNeg));
		outputName += "_cffProb"+cmlProb+(probAllowNeg ? "Neg" : "")+(probFullRup ? "Full" : "");
		
		// new Coulomb filters (path is current preferred)
		// this will use the median interaction between 2 sections, and sum sect-to-sect values across a rupture
//		configBuilder.clusterCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, false,
//				AggregationMethod.SUM, AggregationMethod.PASSTHROUGH,
//				AggregationMethod.RECEIVER_SUM, AggregationMethod.FRACT_POSITIVE), 0.8f); outputName += "_cffJumpPatchNetFract0.8";
//		configBuilder.clusterCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, false,
//				AggregationMethod.SUM, AggregationMethod.PASSTHROUGH,
//				AggregationMethod.RECEIVER_SUM, AggregationMethod.FRACT_POSITIVE), 0.7f); outputName += "_cffJumpPatchNetFract0.7";
		configBuilder.clusterCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, false,
				AggregationMethod.SUM, AggregationMethod.PASSTHROUGH,
				AggregationMethod.RECEIVER_SUM, AggregationMethod.FRACT_POSITIVE), 0.5f); outputName += "_cffJumpPatchNetFract0.5";
//		configBuilder.clusterCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, false,
//				AggregationMethod.NUM_POSITIVE, AggregationMethod.SUM,
//				AggregationMethod.HALF_INTERACTIONS, AggregationMethod.FRACT_POSITIVE), 0.5f); outputName += "_cffJumpPatchHalfIntNetFract0.5";
//		configBuilder.netClusterCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, true,
//				AggregationMethod.SUM, AggregationMethod.SUM,
//				AggregationMethod.SUM, AggregationMethod.SUM), 0f); outputName += "_cffClusterNetPositive";
//		configBuilder.clusterPathCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, false,
//				AggregationMethod.FLATTEN, AggregationMethod.MEDIAN, AggregationMethod.SUM, AggregationMethod.SUM),
//				Range.atLeast(0f)); outputName += "_cffClusterPathPositive";
		configBuilder.clusterPathCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, false,
				AggregationMethod.FLATTEN, AggregationMethod.SUM, AggregationMethod.SUM, AggregationMethod.SUM),
				Range.atLeast(0f)); outputName += "_cffClusterSumPathPositive";
//		configBuilder.parentCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, false,
//				AggregationMethod.FLATTEN, AggregationMethod.SUM, AggregationMethod.SUM, AggregationMethod.SUM),
//				0f, Directionality.EITHER); outputName += "_cffParent";
//		configBuilder.clusterPathCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, false,
//				AggregationMethod.FLATTEN, AggregationMethod.GREATER_SUM_MEDIAN, AggregationMethod.SUM, AggregationMethod.SUM),
//				Range.atLeast(0f)); outputName += "_cffSumMedClusterPathPositive";
//		configBuilder.parentCoulomb(stiffnessCalc, StiffnessAggregationMethod.MEDIAN,
//			0f, Directionality.EITHER); outputName += "_cffParentPositive";
//		configBuilder.clusterCoulomb(
//		stiffnessCalc, StiffnessAggregationMethod.MEDIAN, 0f); outputName += "_cffClusterPositive";
//		configBuilder.netRupCoulomb(stiffnessCalc, StiffnessAggregationMethod.MEDIAN, 0f,
//				RupCoulombQuantity.SUM_SECT_CFF); outputName += "_cffRupNetPositive";
//		configBuilder.netClusterCoulomb(stiffnessCalc,
//				StiffnessAggregationMethod.MEDIAN, 0f); outputName += "_cffClusterNetPositive";

		// Check connectivity only (maximum 2 clusters per rupture)
//		configBuilder.maxNumClusters(2); outputName += "_connOnly";
		
		/*
		 * Splay constraints
		 */
		configBuilder.maxSplays(0); // default, no splays
//		configBuilder.maxSplays(1); outputName += "_max1Splays";
////		configBuilder.splayLength(0.1, true, true); outputName += "_splayLenFract0.1";
//		configBuilder.splayLength(50, false, true); outputName += "_splayLen50km";
		
		/*
		 * Permutation strategies: how should faults be broken up into permutations, combinations of which
		 * will be used to construct ruptures
		 */
		// regular (UCERF3) permutation strategy
		ClusterPermutationStrategy permStrat = new UCERF3ClusterPermuationStrategy();
		// only permutate at connection points or subsection end points
		// (for strict segmentation or testing connection points)
//		ClusterPermutationStrategy permStrat = new ConnectionPointsPermutationStrategy();
//		outputName += "_connPointsPerm";
		// build permutations adaptively (skip over some end points for larger ruptures)
//		float sectFract = 0.05f;
//		SectCountAdaptivePermutationStrategy permStrat = new SectCountAdaptivePermutationStrategy(sectFract, true);
//		configBuilder.add(permStrat.buildConnPointCleanupFilter(connectionStrategy));
//		outputName += "_sectFractPerm"+sectFract;
		
		// build our configuration
		PlausibilityConfiguration config = configBuilder.build();
		outputName += ".zip";
		/*
		 * =============================
		 * END other experiments
		 * =============================
		 */
		
		config.getConnectionStrategy().getClusters();
		if (numAzCached < distAzCalc.getNumCachedAzimuths()
				|| numDistCached < distAzCalc.getNumCachedDistances()) {
			System.out.println("Writing dist/az cache to "+distAzCacheFile.getAbsolutePath());
			distAzCalc.writeCacheFile(distAzCacheFile);
			numAzCached = distAzCalc.getNumCachedAzimuths();
			numDistCached = distAzCalc.getNumCachedDistances();
		}
		
		boolean writeRupSet = (debugCriteria == null || !stopAfterDebug)
				&& outputName != null && rupSetsDir != null;
		if (writeRupSet)
			System.out.println("After building, will write to "+new File(rupSetsDir, outputName));
		
		ClusterRuptureBuilder builder = new ClusterRuptureBuilder(config);
		
		if (debugCriteria != null)
			builder.setDebugCriteria(debugCriteria, stopAfterDebug);
		
		int threads = Integer.max(1, Integer.min(32, Runtime.getRuntime().availableProcessors()-2));
//		int threads = 1;
		System.out.println("Building ruptures with "+threads+" threads...");
		Stopwatch watch = Stopwatch.createStarted();
		List<ClusterRupture> rups = builder.build(permStrat, threads);
		watch.stop();
		double secs = watch.elapsed(TimeUnit.MILLISECONDS)/1000d;
		double mins = (secs / 60d);
		DecimalFormat timeDF = new DecimalFormat("0.00");
		System.out.println("Built "+countDF.format(rups.size())+" ruptures in "+timeDF.format(secs)
			+" secs = "+timeDF.format(mins)+" mins");
		
		if (writeRupSet) {
			// write out test rup set
//			double[] mags = new double[rups.size()];
//			double[] lenghts = new double[rups.size()];
//			double[] rakes = new double[rups.size()];
//			double[] rupAreas = new double[rups.size()];
//			List<List<Integer>> sectionForRups = new ArrayList<>();
//			for (int r=0; r<rups.size(); r++) {
//				List<FaultSection> sects = rups.get(r).buildOrderedSectionList();
//				List<Integer> ids = new ArrayList<>();
//				for (FaultSection sect : sects)
//					ids.add(sect.getSectionId());
//				sectionForRups.add(ids);
//				mags[r] = Double.NaN;
//				rakes[r] = Double.NaN;
//				rupAreas[r] = Double.NaN;
//			}
			double[] sectSlipRates = new double[subSects.size()];
			double[] sectAreasReduced = new double[subSects.size()];
			double[] sectAreasOrig = new double[subSects.size()];
			for (int s=0; s<sectSlipRates.length; s++) {
				FaultSection sect = subSects.get(s);
				sectAreasReduced[s] = sect.getArea(true);
				sectAreasOrig[s] = sect.getArea(false);
				sectSlipRates[s] = sect.getReducedAveSlipRate()*1e-3; // mm/yr => m/yr
			}
			double[] rupMags = new double[rups.size()];
			double[] rupRakes = new double[rups.size()];
			double[] rupAreas = new double[rups.size()];
			double[] rupLengths = new double[rups.size()];
			List<List<Integer>> rupsIDsList = new ArrayList<>();
			for (int r=0; r<rups.size(); r++) {
				ClusterRupture rup = rups.get(r);
				List<FaultSection> rupSects = rup.buildOrderedSectionList();
				List<Integer> sectIDs = new ArrayList<>();
				double totLength = 0d;
				double totArea = 0d;
				double totOrigArea = 0d; // not reduced for aseismicity
				List<Double> sectAreas = new ArrayList<>();
				List<Double> sectRakes = new ArrayList<>();
				for (FaultSection sect : rupSects) {
					sectIDs.add(sect.getSectionId());
					double length = sect.getTraceLength()*1e3;	// km --> m
					totLength += length;
					double area = sectAreasReduced[sect.getSectionId()];	// sq-m
					totArea += area;
					totOrigArea += sectAreasOrig[sect.getSectionId()];	// sq-m
					sectAreas.add(area);
					sectRakes.add(sect.getAveRake());
				}
				rupAreas[r] = totArea;
				rupLengths[r] = totLength;
				rupRakes[r] = FaultUtils.getInRakeRange(FaultUtils.getScaledAngleAverage(sectAreas, sectRakes));
				double origDDW = totOrigArea/totLength;
				rupMags[r] = scale.getMag(totArea, origDDW);
				rupsIDsList.add(sectIDs);
			}
			FaultSystemRupSet rupSet = new FaultSystemRupSet(subSects, sectSlipRates, null, sectAreasReduced, 
					rupsIDsList, rupMags, rupRakes, rupAreas, rupLengths, "");
			rupSet.setPlausibilityConfiguration(config);
			rupSet.setClusterRuptures(rups);
			FaultSystemIO.writeRupSet(rupSet, new File(rupSetsDir, outputName));
		}

		if (numAzCached < distAzCalc.getNumCachedAzimuths()
				|| numDistCached < distAzCalc.getNumCachedDistances()) {
			System.out.println("Writing dist/az cache to "+distAzCacheFile.getAbsolutePath());
			distAzCalc.writeCacheFile(distAzCacheFile);
			System.out.println("DONE writing dist/az cache");
		}
		
		if (stiffnessCache != null && stiffnessCacheFile != null
				&& stiffnessCacheSize < stiffnessCache.calcCacheSize()) {
			System.out.println("Writing stiffness cache to "+stiffnessCacheFile.getAbsolutePath());
			stiffnessCache.writeCacheFile(stiffnessCacheFile);
			System.out.println("DONE writing stiffness cache");
		}
	}

}
