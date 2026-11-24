FROM node:22-alpine AS builder
# 与 r11 Dockerfile 唯一不同：构建镜像从 node:20-alpine 升到 node:22-alpine。
# Solid 2.0 / Vite 8 要求 Node 22.12+；node:20-alpine 的 20.x 在文档下限之外。
# 其余与 r11 一致（拷 dist → nginx:alpine，端口 3000）。

WORKDIR /app

COPY package.json .
RUN npm install

COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 3000
CMD ["nginx", "-g", "daemon off;"]
