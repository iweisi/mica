package net.dreamlu.mica.test.utils;

import net.dreamlu.mica.core.utils.ClassUtil;
import org.springframework.core.convert.converter.Converter;

import java.util.Map;

/**
 * TODO asm 字节码 测试，用于未来 有一天 比较无聊的时候 优化 BeanCopy Map -》 Bean
 *
 * 利用 idea [Asm ByteCode viewer] 插件查看生成代码
 *
 * @author L.cm
 */
public class AsmTest {

	public void copy(User user, Map<String, Object> userMap, Converter var3) {
		Object id = userMap.get("id");
		if (id != null) {
			// 不做类型转换生成的代码
			if (ClassUtil.isAssignableValue(Integer.class, id)) {
				// 此处 需要 asm 做 类型转换 判断
				user.setId((Integer) id);
			} else {
				// 做类型转换的代码
				user.setId((Integer) var3.convert(id));
			}
		}
	}
}
