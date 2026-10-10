import { describe, expect, it } from 'vitest';
import { device, measured, net, via } from './rum';

describe('замеры скорости', () => {
	it('устройство, сеть и путь', () => {
		expect(device(false, 900)).toBe('computer');
		expect(device(true, 390)).toBe('phone');
		expect(device(true, 820)).toBe('tablet');
		expect(net('wifi')).toBe('wifi');
		expect(net('bluetooth')).toBe('other');
		expect(net(undefined)).toBe('unknown');
		expect(via('192.168.1.20')).toBe('local');
		expect(via('172.20.0.5')).toBe('local');
		expect(via('macbook.local')).toBe('local');
		expect(via('bik2401.fxtun.dev')).toBe('tunnel');
		expect(via('172.32.0.1')).toBe('tunnel');
	});

	it('поток событий и сами замеры не меряются', () => {
		expect(measured('https://x/api/today')).toBe(true);
		expect(measured('https://x/api/live')).toBe(false);
		expect(measured('https://x/api/monitor/timings')).toBe(false);
		expect(measured('https://x/_app/immutable/a.js')).toBe(false);
	});
});
