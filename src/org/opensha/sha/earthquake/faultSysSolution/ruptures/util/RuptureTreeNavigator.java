package org.opensha.sha.earthquake.faultSysSolution.ruptures.util;

import java.util.Collection;

import org.opensha.sha.earthquake.faultSysSolution.ruptures.FaultSubsectionCluster;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.Jump;
import org.opensha.sha.faultSurface.FaultSection;

public interface RuptureTreeNavigator {

	/**
	 * @param cluster
	 * @return the predecessor (up a level in the tree) to this cluster, or null if this is the
	 * top level cluster
	 */
	public FaultSubsectionCluster getPredecessor(FaultSubsectionCluster cluster);

	/**
	 * @param cluster
	 * @return a list of all descendants (down a level in the tree) from this cluster (empty list is
	 * returned if no descendants exist)
	 */
	public Collection<FaultSubsectionCluster> getDescendants(FaultSubsectionCluster cluster);

	/**
	 * Locates the given jump. If the jump occurs in the rupture backwards, i.e., from toCluster to
	 * fromCluster, then the jump returned will be reversed such that it matches the supplied to/from clusters
	 * 
	 * @param fromCluster
	 * @param toCluster
	 * @return the jump from fromCluster to toCluster
	 * @throws IllegalStateException if the rupture does not use a direct jump between these clusters
	 */
	public Jump getJump(FaultSubsectionCluster fromCluster, FaultSubsectionCluster toCluster);

	/**
	 * @param sect
	 * @return the direct predecessor of this section (either within its cluster or in the previous cluster)
	 * null if this is the first section of the first cluster
	 */
	public FaultSection getPredecessor(FaultSection sect);

	/**
	 * 
	 * @param sect
	 * @return a list of all descendants from this subsection, which could be in the same
	 * cluster or on the other side of a jump (empty list is returned if no descendants exist)
	 */
	public Collection<FaultSection> getDescendants(FaultSection sect);

}