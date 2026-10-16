const validId = /^[a-z0-9_.-]+:[a-z0-9_][a-z0-9_./-]*$/;
export function normalizePose(msg) {
  if (msg?.type !== 'pose' || !['x', 'y', 'z', 'yaw', 'pitch'].every(k => Number.isFinite(msg[k]))) return null;
  if (Math.abs(msg.x) > 30_000_000 || Math.abs(msg.z) > 30_000_000 || msg.y < -4096 || msg.y > 4096) return null;
  if (typeof msg.dimension !== 'string' || msg.dimension.length > 128 || !validId.test(msg.dimension)) return null;
  const angle = value => Number.isFinite(value) ? ((value % 360) + 360) % 360 : 0;
  const pose = { x: Math.round(msg.x * 100) / 100, y: Math.round(msg.y * 100) / 100, z: Math.round(msg.z * 100) / 100,
    yaw: angle(msg.yaw), pitch: Math.max(-90, Math.min(90, msg.pitch)), bodyYaw: angle(msg.bodyYaw ?? msg.yaw),
    headYaw: angle(msg.headYaw ?? msg.yaw), dimension: msg.dimension,
    stance: ['STANDING', 'CROUCHING', 'SWIMMING', 'FALL_FLYING', 'SLEEPING', 'SPIN_ATTACK'].includes(msg.stance) ? msg.stance : 'STANDING',
    mainArm: msg.mainArm === 'LEFT' ? 'LEFT' : 'RIGHT',
    useHand: msg.useHand === 'OFF_HAND' ? 'OFF_HAND' : 'MAIN_HAND',
    swingHand: msg.swingHand === 'OFF_HAND' ? 'OFF_HAND' : 'MAIN_HAND',
    swing: Number.isSafeInteger(msg.swing) && msg.swing >= 0 && msg.swing <= 2147483647 ? msg.swing : 0,
    modelParts: Number.isInteger(msg.modelParts) ? msg.modelParts & 127 : 127, equipment: {} };
  for (const key of ['sneaking', 'sprinting', 'swimming', 'fallFlying', 'onGround', 'invisible', 'usingItem']) pose[key] = msg[key] === true;
  for (const slot of ['MAINHAND', 'OFFHAND', 'HEAD', 'CHEST', 'LEGS', 'FEET']) {
    const value = msg.equipment?.[slot];
    pose.equipment[slot] = typeof value === 'string' && value.length <= 128 && validId.test(value) ? value : 'minecraft:air';
  }
  return pose;
}
