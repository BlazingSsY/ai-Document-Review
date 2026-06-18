FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# 子路径部署：构建时传 --build-arg APP_BASE=/office-app/ 即可把前端挂到该前缀；默认根路径 /。
ARG APP_BASE=/
ENV APP_BASE=$APP_BASE
RUN npm run build

FROM nginx:latest
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
